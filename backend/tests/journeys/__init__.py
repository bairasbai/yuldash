# Пакет, а не просто папка: иначе pytest регистрирует здешний conftest.py под тем же именем
# `conftest`, что и tests/conftest.py, и `from conftest import upload_doc` в соседних тестах
# начинает смотреть сюда (45 тестов падали на сборе, 13.10.2026).
