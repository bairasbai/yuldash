"""TEST01: денежный concurrency-тест не должен ждать вложенную транзакцию синхронно.

Этот сторож не изображает PostgreSQL на SQLite и не утверждает, что локальный прогон
воспроизвёл зависание. Он фиксирует опасную конструкцию в исходнике теста: callback,
вызванный внутри settlement, открывает вторую Session и до возврата синхронно запускает
тот же settlement. На PostgreSQL второй вызов может ждать строковый замок первого, тогда
как первый ждёт возврата второго.
"""
from __future__ import annotations

import ast
from pathlib import Path
import re


TEST_FILES = (
    "test_wallet_is_not_charged_twice.py",
    "test_the_wallet_works_one_way_for_couriers.py",
)
SETTLEMENT_CALLS = {
    "debt_mod.settle_debt_from_wallet",
    "courier_mod.settle_courier_commission_from_wallet",
}


def _qualified_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        prefix = _qualified_name(node.value)
        return f"{prefix}.{node.attr}" if prefix else node.attr
    return ""


def _opens_session(function: ast.FunctionDef) -> bool:
    return any(
        isinstance(node, (ast.With, ast.AsyncWith))
        and any(
            isinstance(item.context_expr, ast.Call)
            and _qualified_name(item.context_expr.func) == "Session"
            for item in node.items
        )
        for node in ast.walk(function)
    )


def _calls_settlement(function: ast.FunctionDef) -> bool:
    return any(
        isinstance(node, ast.Call)
        and _qualified_name(node.func) in SETTLEMENT_CALLS
        for node in ast.walk(function)
    )


def _dangerous_nested_settlements(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    found: list[str] = []
    for test in (
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.FunctionDef) and node.name.startswith("test_")
    ):
        submitted_workers = {
            call.args[0].id
            for call in ast.walk(test)
            if isinstance(call, ast.Call)
            and isinstance(call.func, ast.Attribute)
            and call.func.attr == "submit"
            and call.args
            and isinstance(call.args[0], ast.Name)
        }
        for nested in (
            node
            for node in ast.walk(test)
            if isinstance(node, ast.FunctionDef) and node is not test
        ):
            # Worker, переданный в ThreadPoolExecutor.submit, выполняется независимо.
            # Опасен callback, который outer settlement вызывает синхронно в своём стеке.
            if (
                nested.name not in submitted_workers
                and _opens_session(nested)
                and _calls_settlement(nested)
            ):
                found.append(f"{path.name}::{test.name} -> {nested.name}")
    return found


def _submitted_settlement_workers(path: Path) -> list[tuple[str, ast.FunctionDef, ast.FunctionDef]]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    found: list[tuple[str, ast.FunctionDef, ast.FunctionDef]] = []
    for test in (
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.FunctionDef) and node.name.startswith("test_")
    ):
        submitted = {
            call.args[0].id
            for call in ast.walk(test)
            if isinstance(call, ast.Call)
            and isinstance(call.func, ast.Attribute)
            and call.func.attr == "submit"
            and call.args
            and isinstance(call.args[0], ast.Name)
        }
        for worker in (
            node
            for node in ast.walk(test)
            if isinstance(node, ast.FunctionDef) and node.name in submitted
        ):
            if _opens_session(worker) and _calls_settlement(worker):
                found.append((f"{path.name}::{test.name} -> {worker.name}", test, worker))
    return found


def _postgres_timeout_seconds(worker: ast.FunctionDef, setting: str) -> float | None:
    for branch in (node for node in ast.walk(worker) if isinstance(node, ast.If)):
        condition = ast.unparse(branch.test)
        if "engine.dialect.name" not in condition or "postgresql" not in condition:
            continue
        for node in (child for statement in branch.body for child in ast.walk(statement)):
            if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
                continue
            match = re.search(rf"SET LOCAL {setting}\s*=\s*'(\d+)(ms|s)'", node.value)
            if match:
                value = float(match.group(1))
                return value / 1000 if match.group(2) == "ms" else value
    return None


def _future_timeout_seconds(test: ast.FunctionDef) -> float | None:
    values = [
        float(keyword.value.value)
        for call in ast.walk(test)
        if isinstance(call, ast.Call)
        and isinstance(call.func, ast.Attribute)
        and call.func.attr == "result"
        for keyword in call.keywords
        if keyword.arg == "timeout"
        and isinstance(keyword.value, ast.Constant)
        and isinstance(keyword.value.value, (int, float))
    ]
    return min(values) if values else None


def test_money_race_checks_do_not_run_nested_settlement_synchronously():
    tests_dir = Path(__file__).resolve().parent
    dangerous = [
        item
        for name in TEST_FILES
        for item in _dangerous_nested_settlements(tests_dir / name)
    ]

    assert not dangerous, (
        "Найден синхронный nested settlement внутри уже идущего settlement-теста. "
        "SQLite не доказывает безопасность этого шаблона для PostgreSQL; используй две "
        "независимые задачи/Session с barrier и ограниченным future.result(timeout=...).\n"
        + "\n".join(dangerous)
    )


def test_each_concurrent_settlement_has_postgresql_server_timeouts_below_future_timeout():
    tests_dir = Path(__file__).resolve().parent
    workers = [
        worker
        for name in TEST_FILES
        for worker in _submitted_settlement_workers(tests_dir / name)
    ]

    assert len(workers) == 2, (
        "Ожидались две независимые settlement-задачи — такси и курьер; "
        f"найдено: {[label for label, _, _ in workers]}"
    )
    failures: list[str] = []
    for label, test, worker in workers:
        lock_timeout = _postgres_timeout_seconds(worker, "lock_timeout")
        statement_timeout = _postgres_timeout_seconds(worker, "statement_timeout")
        future_timeout = _future_timeout_seconds(test)
        if not (
            lock_timeout is not None
            and statement_timeout is not None
            and future_timeout is not None
            and 0 < lock_timeout < statement_timeout < future_timeout
        ):
            failures.append(
                f"{label}: lock={lock_timeout}, statement={statement_timeout}, "
                f"future={future_timeout}"
            )

    assert not failures, (
        "PostgreSQL должен сам прервать ожидание до Future timeout, иначе "
        "ThreadPoolExecutor.__exit__ снова может ждать поток без границы:\n"
        + "\n".join(failures)
    )
