import { useCallback, useEffect, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  deleteDriverDocs,
  exportMyData,
  fetchMyData,
  type MyData,
} from "../api/myData";
import { ErrorState } from "../components/States";
import {
  IconBell,
  IconCar,
  IconChat,
  IconMic,
  IconPin,
  IconReceipt,
  IconShield,
  IconTicket,
  IconTrash,
  IconWallet,
} from "../components/Icons";
import { SubHeader } from "./ConsentsScreen";

type IconType = (props: { size?: number }) => JSX.Element;
type ViewState = "loading" | "error" | "ready";

function pluralRu(n: number, one: string, few: string, many: string): string {
  const mod100 = Math.abs(n) % 100;
  const mod10 = mod100 % 10;
  if (mod100 >= 11 && mod100 <= 14) return many;
  if (mod10 === 1) return one;
  if (mod10 >= 2 && mod10 <= 4) return few;
  return many;
}

function DataRow({
  Icon,
  title,
  value,
  note,
}: {
  Icon: IconType;
  title: string;
  value: string;
  note: string;
}) {
  return (
    <div className="my-data-row">
      <span className="my-data-row__icon" aria-hidden><Icon size={22} /></span>
      <span className="my-data-row__main">
        <strong>{title}</strong>
        <small>{note}</small>
      </span>
      <strong className="my-data-row__value">{value}</strong>
    </div>
  );
}

function LoadingState({ text }: { text: string }) {
  return (
    <div className="state my-data-state" aria-live="polite">
      <span className="mini-spinner my-data-spinner" aria-hidden />
      <h2>{text}</h2>
    </div>
  );
}

export default function MyDataScreen() {
  const { appText, lang } = useLang();
  const navigate = useNavigate();
  const [state, setState] = useState<ViewState>("loading");
  const [data, setData] = useState<MyData | null>(null);
  const [askDelete, setAskDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [docsError, setDocsError] = useState("");
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");
  const cancelRef = useRef<HTMLButtonElement>(null);
  const deleteTriggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchMyData(signal)
      .then((result) => {
        setData(result);
        setState("ready");
      })
      .catch((error) => {
        if (signal?.aborted || error?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    if (askDelete) cancelRef.current?.focus();
  }, [askDelete]);

  const count = (n: number, one: string, few: string, many: string, ba: string) =>
    lang === "ba" ? `${n} ${ba}` : `${n} ${pluralRu(n, one, few, many)}`;
  const selfDelete = (days: number) =>
    appText(
      `Исчезнут сами через ${days} ${pluralRu(days, "день", "дня", "дней")}`,
      `${days} көндән үҙҙәре юғала`
    );

  async function removeDriverDocs() {
    if (deleting) return;
    setDeleting(true);
    setDocsError("");
    try {
      await deleteDriverDocs();
      setAskDelete(false);
      load();
    } catch (error) {
      setDocsError(
        error instanceof ApiError
          ? error.message
          : appText("Не получилось удалить. Попробуй ещё раз.", "Юйып булманы. Тағы бер тапҡыр ҡара.")
      );
    } finally {
      setDeleting(false);
    }
  }

  function closeDeleteDialog() {
    if (deleting) return;
    setAskDelete(false);
    setDocsError("");
    window.setTimeout(() => deleteTriggerRef.current?.focus(), 0);
  }

  function keepDialogFocus(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      closeDeleteDialog();
      return;
    }
    if (event.key !== "Tab") return;
    const buttons = dialogRef.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)");
    if (!buttons?.length) return;
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  async function downloadExport() {
    if (exporting) return;
    setExporting(true);
    setExportError("");
    try {
      const dump = await exportMyData(lang === "ba" ? "ba" : "ru");
      const filename = dump.filename.replace(/[\\/:*?"<>|]/g, "_").slice(0, 120) || "yuldash-data.txt";
      const file = new File([dump.text], filename, { type: "text/plain;charset=utf-8" });
      if (navigator.share && navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: filename });
      } else {
        const url = URL.createObjectURL(file);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = filename;
        anchor.hidden = true;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 0);
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") return;
      setExportError(
        error instanceof ApiError
          ? error.message
          : appText(
              "Не получилось собрать файл. Попробуй ещё раз.",
              "Файл йыйып булманы. Тағы бер тапҡыр ҡара."
            )
      );
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <SubHeader title={appText("Мои данные", "Минең мәғлүмәттәр")} onBack={() => navigate(-1)} />
      <p className="screen-lead">
        {appText(
          "Вот всё, что Юлдаш о тебе хранит — и когда оно исчезнет само.",
          "Юлдаш һинең хаҡта нимә һаҡлай — һәм ул үҙе ҡасан юғала."
        )}
      </p>

      {state === "loading" && (
        <LoadingState text={appText("Смотрим, что у нас есть…", "Нимә барлығын ҡарайбыҙ…")} />
      )}
      {state === "error" && (
        <ErrorState
          title={appText("Не удалось загрузить данные", "Мәғлүмәттәрҙе йөкләп булманы")}
          hint={appText("Проверь связь и попробуй ещё раз.", "Бәйләнеште тикшереп, тағы ҡабатла.")}
          onRetry={() => load()}
        />
      )}

      {state === "ready" && data && (
        <div className="my-data-stack">
          <section className="my-data-card" style={{ "--data-delay": "0ms" } as CSSProperties}>
            <DataRow
              Icon={IconCar}
              title={appText("Поездки", "Сәфәрҙәр")}
              value={count(data.rides, "поездка", "поездки", "поездок", "сәфәр")}
              note={selfDelete(data.rides_days)}
            />
            <DataRow
              Icon={IconTicket}
              title={appText("Забронированные места", "Алынған урындар")}
              value={count(data.bookings, "бронь", "брони", "броней", "урын")}
              note={selfDelete(data.rides_days)}
            />
            <DataRow
              Icon={IconChat}
              title={appText("Сообщения в чатах", "Чаттарҙағы хәбәрҙәр")}
              value={count(data.messages, "сообщение", "сообщения", "сообщений", "хәбәр")}
              note={selfDelete(data.messages_days)}
            />
            <DataRow
              Icon={IconMic}
              title={appText("Голосовые", "Тауышлы хәбәрҙәр")}
              value={count(data.voices, "запись", "записи", "записей", "яҙма")}
              note={selfDelete(data.voices_days)}
            />
            <DataRow
              Icon={IconBell}
              title={appText("Уведомления", "Иҫкәртеүҙәр")}
              value={count(data.notifications, "штука", "штуки", "штук", "дана")}
              note={selfDelete(data.notifications_days)}
            />
          </section>

          {data.driver_docs > 0 && (
            <section className="my-data-card my-data-card--padded" style={{ "--data-delay": "180ms" } as CSSProperties}>
              <div className="my-data-card__head">
                <span className="my-data-row__icon" aria-hidden><IconShield size={22} /></span>
                <span className="my-data-row__main">
                  <strong>{appText("Документы водителя", "Шофер документтары")}</strong>
                  <small>{appText("Хранятся, пока ты сам их не удалишь", "Үҙең юймайынса һаҡлана")}</small>
                </span>
                <strong className="my-data-row__value">
                  {count(data.driver_docs, "файл", "файла", "файлов", "файл")}
                </strong>
              </div>
              {data.driver_docs_removable ? (
                <button
                  ref={deleteTriggerRef}
                  type="button"
                  className="btn-danger my-data-full-btn"
                  onClick={() => setAskDelete(true)}
                >
                  <IconTrash size={18} /> {appText("Удалить документы", "Документтарҙы юйырға")}
                </button>
              ) : (
                <p className="my-data-card__note">
                  {appText(
                    "Пока идёт проверка или ты на линии — удалить нельзя. Закончится проверка, сойдёшь с линии — кнопка появится.",
                    "Тикшереү барғанда йәки линияла булғанда — юйып булмай. Тикшереү бөткәс, линиянан сыҡҡас — төймә килеп сыға."
                  )}
                </p>
              )}
            </section>
          )}

          <section className="my-data-card" style={{ "--data-delay": "260ms" } as CSSProperties}>
            <h2 className="my-data-card__title">{appText("Чего у нас нет", "Беҙҙә юҡ нәмәләр")}</h2>
            {!data.location_stored && (
              <DataRow
                Icon={IconPin}
                title={appText("Точная геолокация", "Теүәл геолокация")}
                value={appText("Не храним", "Һаҡламайбыҙ")}
                note={appText(
                  "Видно только попутчикам и только во время поездки",
                  "Юлдаштарға ғына һәм сәфәр барышында ғына күренә"
                )}
              />
            )}
            {!data.card_stored && (
              <DataRow
                Icon={IconWallet}
                title={appText("Данные карты", "Карта мәғлүмәттәре")}
                value={appText("Не храним", "Һаҡламайбыҙ")}
                note={appText(
                  "Деньги идут мимо нас — напрямую водителю",
                  "Аҡса беҙҙән үтмәй — тура шоферға бара"
                )}
              />
            )}
          </section>

          <section className="my-data-card my-data-card--padded" style={{ "--data-delay": "320ms" } as CSSProperties}>
            <div className="my-data-card__head">
              <span className="my-data-row__icon" aria-hidden><IconReceipt size={22} /></span>
              <span className="my-data-row__main">
                <strong>{appText("Скачать мои данные", "Мәғлүмәттәремде йөкләү")}</strong>
                <small>
                  {appText(
                    "Обычный текстовый файл — откроется на любом телефоне",
                    "Ябай текст файлы — теләһә ниндәй телефонда асыла"
                  )}
                </small>
              </span>
            </div>
            <button
              type="button"
              className="btn-soft my-data-full-btn"
              onClick={downloadExport}
              disabled={exporting}
            >
              {exporting ? <span className="mini-spinner" aria-hidden /> : <IconReceipt size={18} />}
              {appText("Скачать", "Йөкләргә")}
            </button>
            {exportError && <p className="my-data-error" role="alert">{exportError}</p>}
          </section>

          <p className="my-data-footnote">
            {appText(
              "Сроки — те же, по которым база чистится сама. Ничего вручную удалять не нужно.",
              "Ваҡыттар — база үҙе таҙарынған ваҡыттар. Ҡулдан бер нәмә лә юйырға кәрәкмәй."
            )}
          </p>
        </div>
      )}

      {askDelete && (
        <div className="sheet-backdrop" onMouseDown={closeDeleteDialog}>
          <section
            ref={dialogRef}
            className="my-data-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="delete-docs-title"
            onMouseDown={(event) => event.stopPropagation()}
            onKeyDown={keepDialogFocus}
          >
            <div className="state__icon state__icon--danger" aria-hidden><IconTrash size={34} /></div>
            <h2 id="delete-docs-title">{appText("Удалить документы?", "Документтарҙы юяһыңмы?")}</h2>
            <p>
              {appText(
                "Фото прав и машины удалятся с сервера навсегда. Вместе с ними снимется галочка «проверен» — пассажиры перестанут её видеть.",
                "Права һәм машина фотоһы серверҙан бөтөнләй юйыла. Улар менән бергә «тикшерелгән» билдәһе лә юйыла — юлсылар уны күрмәйәсәк."
              )}
            </p>
            <p>
              {appText(
                "Аккаунт и поездки останутся. Захочешь возить снова — просто загрузишь документы заново.",
                "Иҫәп һәм сәфәрҙәр ҡала. Тағы йөрөтөргә теләһәң — документтарҙы яңынан һалаһың."
              )}
            </p>
            {docsError && <p className="my-data-error" role="alert">{docsError}</p>}
            <div className="my-data-dialog__actions">
              <button
                ref={cancelRef}
                type="button"
                className="btn-ghost"
                disabled={deleting}
                onClick={closeDeleteDialog}
              >
                {appText("Отмена", "Баш тартыу")}
              </button>
              <button type="button" className="btn-danger" disabled={deleting} onClick={removeDriverDocs}>
                {deleting ? appText("Удаляем…", "Юябыҙ…") : appText("Удалить", "Юйырға")}
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
