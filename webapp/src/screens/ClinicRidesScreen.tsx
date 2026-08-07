// ================================================================
//  «Поездки к клинике» — B2B медцентр-партнёр (medical.py).
//  Публичная витрина: справочник клиник GET /medical-partners,
//  выбор клиники → попутки к ней GET /medical-partners/{id}/rides.
//  Это ЛОГИСТИКА, не медуслуга. Мягкая деградация 404 (до деплоя).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchClinicRides,
  fetchMedicalPartners,
  type MedicalPartner,
} from "../api/medical";
import type { Ride } from "../api/rides";
import RideCard from "../components/RideCard";
import RideSheet from "../components/RideSheet";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconHospital, IconChevron, IconCar } from "../components/Icons";

type Status = "loading" | "error" | "ready" | "soon";

export default function ClinicRidesScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [partners, setPartners] = useState<MedicalPartner[]>([]);
  const [selected, setSelected] = useState<MedicalPartner | null>(null);
  const [rides, setRides] = useState<Ride[]>([]);
  const [ridesStatus, setRidesStatus] = useState<Status>("ready");
  const [sheet, setSheet] = useState<Ride | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchMedicalPartners(undefined, signal)
      .then((rows) => {
        setPartners(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Справочника ещё нет на проде (release-2026-07) → аккуратное «скоро».
        if (e instanceof ApiError && e.status === 404) setStatus("soon");
        else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  function openClinic(p: MedicalPartner) {
    setSelected(p);
    setRidesStatus("loading");
    setRides([]);
    fetchClinicRides(p.id)
      .then((r) => {
        setRides(r.items);
        setRidesStatus("ready");
      })
      .catch(() => setRidesStatus("error"));
  }

  // Детали выбранной клиники
  if (selected) {
    return (
      <>
        <SubHeader
          title={selected.name}
          subtitle={`${selected.city}${selected.address ? ` · ${selected.address}` : ""}`}
          onBack={() => setSelected(null)}
        />

        {selected.description && (
          <p className="clinic-desc">{selected.description}</p>
        )}

        <h2 className="section-title">{appText("Попутки к клинике", "Клиникаға юлдаштар")}</h2>

        {ridesStatus === "loading" && <LoadingList count={2} />}
        {ridesStatus === "error" && <ErrorState onRetry={() => openClinic(selected)} />}
        {ridesStatus === "ready" &&
          (rides.length === 0 ? (
            <div className="state">
              <div className="state__icon"><IconCar size={34} /></div>
              <h2>{appText("Пока никто не едет", "Әле бер кем бармай")}</h2>
              <p>
                {appText(
                  "Оставь заявку — водители увидят, что нужно доехать до клиники.",
                  "Заявка ҡалдыр — водителдәр клиникаға барырға кәрәклеген күрер."
                )}
              </p>
              <button type="button" className="btn-primary" onClick={() => navigate("/request")}>
                {appText("Создать заявку", "Заявка ҡалдыр")}
              </button>
            </div>
          ) : (
            <div>
              {rides.map((ride, i) => (
                <button
                  key={ride.id}
                  type="button"
                  className="ride-card-btn"
                  onClick={() => setSheet(ride)}
                >
                  <RideCard ride={ride} index={i} />
                </button>
              ))}
            </div>
          ))}

        {sheet && <RideSheet ride={sheet} onClose={() => setSheet(null)} />}
      </>
    );
  }

  // Список клиник
  return (
    <>
      <SubHeader
        title={appText("Поездки к клинике", "Клиникаға сәфәр")}
        subtitle={appText("Доехать до больницы вместе с соседями", "Дауаханаға күршеләр менән бергә барырға")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}
      {status === "soon" && (
        <div className="state">
          <div className="state__icon"><IconHospital size={34} /></div>
          <h2>{appText("Скоро появится", "Тиҙҙән буласаҡ")}</h2>
          <p>
            {appText(
              "Справочник клиник подключим с ближайшим обновлением сервиса.",
              "Клиникалар белешмәһен яҡын яңыртыуҙа тоташтырабыҙ."
            )}
          </p>
        </div>
      )}
      {status === "ready" &&
        (partners.length === 0 ? (
          <div className="state">
            <div className="state__icon"><IconHospital size={34} /></div>
            <h2>{appText("Клиник пока нет", "Әле клиникалар юҡ")}</h2>
            <p>{appText("Справочник скоро наполнится.", "Белешмә тиҙҙән тулыр.")}</p>
          </div>
        ) : (
          <div className="list">
            {partners.map((p) => (
              <button
                key={p.id}
                type="button"
                className="list-row list-row--link"
                onClick={() => openClinic(p)}
              >
                <span className="list-row__icon">
                  <IconHospital size={22} />
                </span>
                <div className="list-row__main">
                  <div className="list-row__title">{p.name}</div>
                  <div className="list-row__sub">
                    {p.city}
                    {p.address ? ` · ${p.address}` : ""}
                  </div>
                </div>
                <span className="list-row__chev">
                  <IconChevron size={20} />
                </span>
              </button>
            ))}
          </div>
        ))}
    </>
  );
}
