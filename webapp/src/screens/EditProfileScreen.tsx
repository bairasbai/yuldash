// ================================================================
//  «Мой профиль» — редактирование (POST /me/update + /upload/photo).
//  Зеркало Android ProfileScreen.kt (блок редактирования).
//
//  На сайте этого не было вообще: имя приходило из входа и застревало
//  навсегда, фото поставить было нечем, город и пол — недоступны.
//
//  Пол здесь не «для статистики»: отметку «только женщины» сервер
//  проверяет у ОБЕИХ сторон поездки. Без указанного пола женщина не
//  сможет ни забронировать такую поездку, ни осмысленно её создать.
//  Поэтому рядом стоит честное объяснение, зачем мы спрашиваем.
//
//  Телефон не редактируется никогда — он привязан к входу.
// ================================================================
import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { updateMe, uploadProfilePhoto } from "../api/auth";
import { SubHeader } from "./ConsentsScreen";
import { IconCamera, IconCheck, IconLock, IconProfile } from "../components/Icons";

type Gender = "" | "female" | "male";

export default function EditProfileScreen() {
  const { appText } = useLang();
  const { user, refresh } = useAuth();
  const navigate = useNavigate();

  const [name, setName] = useState(user?.name ?? "");
  const [city, setCity] = useState(user?.city ?? "");
  const [gender, setGender] = useState<Gender>((user?.gender ?? "") as Gender);
  const [avatar, setAvatar] = useState(user?.avatar_url ?? "");
  const [busy, setBusy] = useState(false);
  const [photoBusy, setPhotoBusy] = useState(false);
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const fileRef = useRef<HTMLInputElement | null>(null);

  const genders: { key: Gender; ru: string; ba: string }[] = [
    { key: "female", ru: "Женщина", ba: "Ҡатын-ҡыҙ" },
    { key: "male", ru: "Мужчина", ba: "Ир-ат" },
    { key: "", ru: "Не указывать", ba: "Күрһәтмәҫкә" },
  ];

  async function pickPhoto(file: File | undefined) {
    if (!file || photoBusy) return;
    setPhotoBusy(true);
    setError("");
    try {
      const { url } = await uploadProfilePhoto(file);
      if (url) {
        await updateMe({ avatar_url: url });
        setAvatar(url);
        await refresh();
        setNote(appText("Фото обновлено", "Фото яңыртылды"));
      }
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не удалось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setPhotoBusy(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  }

  async function save() {
    if (busy) return;
    setBusy(true);
    setError("");
    setNote("");
    try {
      // Шлём только то, что человек реально поменял: сервер обновляет присланные поля.
      const body: Parameters<typeof updateMe>[0] = {};
      if (name.trim() !== (user?.name ?? "")) body.name = name.trim();
      if (city.trim() !== (user?.city ?? "")) body.city = city.trim();
      if (gender !== ((user?.gender ?? "") as Gender)) body.gender = gender;
      if (Object.keys(body).length === 0) {
        setNote(appText("Ничего не изменилось", "Бер нәмә лә үҙгәрмәне"));
        return;
      }
      await updateMe(body);
      await refresh();
      setNote(appText("Сохранено", "Һаҡланды"));
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не удалось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Мой профиль", "Минең профиль")}
        subtitle={appText("Имя, фото, город и пол", "Исем, фото, ҡала һәм енес")}
        onBack={() => navigate(-1)}
      />

      {/* Фото профиля */}
      <div className="act-card">
        <div className="act-card__title">
          <IconCamera size={18} /> {appText("Фото профиля", "Профиль фотоһы")}
        </div>
        <p className="act-card__text">
          {appText(
            "С фото попутчику спокойнее — он видит, кого ждать у машины.",
            "Фото менән юлдашҡа тынысыраҡ — кемде көтөргә икәнен күрә."
          )}
        </p>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span className="profile-card__avatar" aria-hidden>
            {avatar ? <img src={avatar} alt="" /> : <IconProfile size={28} />}
          </span>
          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => void pickPhoto(e.target.files?.[0])}
          />
          <button
            type="button"
            className="btn-soft"
            onClick={() => fileRef.current?.click()}
            disabled={photoBusy}
          >
            {photoBusy
              ? appText("Загружаем…", "Йөкләйбеҙ…")
              : avatar
                ? appText("Изменить фото", "Фотоны үҙгәртеү")
                : appText("Добавить фото", "Фото өҫтәү")}
          </button>
        </div>
      </div>

      {/* Имя и город */}
      <label className="field" style={{ marginTop: 14 }}>
        <span className="field__label">{appText("Твоё имя", "Һинең исем")}</span>
        <input
          className="field__input"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={120}
          placeholder={appText("Как тебя зовут", "Исемең")}
        />
      </label>

      <label className="field" style={{ marginTop: 12 }}>
        <span className="field__label">{appText("Родной город", "Тыуған ҡала")}</span>
        <input
          className="field__input"
          value={city}
          onChange={(e) => setCity(e.target.value)}
          maxLength={80}
          placeholder={appText("Например, Сибай", "Мәҫәлән, Сибай")}
        />
        <span className="field__hint">
          {appText(
            "Покажем скидки и посылки рядом с тобой.",
            "Һиңә яҡын ташламаларҙы һәм аҫылмаларҙы күрһәтәбеҙ."
          )}
        </span>
      </label>

      {/* Пол — с честным объяснением, зачем спрашиваем */}
      <span className="field__label" style={{ marginTop: 14, display: "block" }}>
        {appText("Пол", "Енес")}
      </span>
      <div className="seg" style={{ marginTop: 6 }}>
        {genders.map((g) => (
          <button
            key={g.key || "none"}
            type="button"
            className={"seg__item" + (gender === g.key ? " is-active" : "")}
            onClick={() => setGender(g.key)}
          >
            {appText(g.ru, g.ba)}
          </button>
        ))}
      </div>
      <p className="demand__quiet">
        {appText(
          "Нужен только для поездок с отметкой «Только женщины» — чтобы это обещание было настоящим. Больше нигде не показывается.",
          "Тик «Тик ҡатын-ҡыҙ» билдәле сәфәрҙәр өсөн кәрәк — был вәғәҙә ысын булһын өсөн. Башҡа бер ҡайҙа ла күренмәй."
        )}
      </p>

      {/* Телефон — не редактируется */}
      <div className="list" style={{ marginTop: 14 }}>
        <div className="list-row">
          <span className="list-row__icon">
            <IconLock size={22} />
          </span>
          <div className="list-row__main">
            <div className="list-row__title">{user?.phone || appText("Телефон", "Телефон")}</div>
            <div className="list-row__sub">
              {appText(
                "Телефон скрыт до подтверждения поездки и не меняется — по нему ты входишь.",
                "Телефон сәфәр раҫланғанға тиклем йәшерен һәм үҙгәрмәй — уның менән инәһең."
              )}
            </div>
          </div>
        </div>
      </div>

      {error && <div className="auth__error">{error}</div>}
      {note && <div className="consents__status ok">{note}</div>}

      <button
        type="button"
        className="btn-primary submit-btn"
        style={{ marginTop: 14 }}
        onClick={save}
        disabled={busy}
      >
        {busy ? (
          appText("Сохраняем…", "Һаҡлайбыҙ…")
        ) : (
          <>
            <IconCheck size={18} /> {appText("Сохранить", "Һаҡларға")}
          </>
        )}
      </button>
    </>
  );
}
