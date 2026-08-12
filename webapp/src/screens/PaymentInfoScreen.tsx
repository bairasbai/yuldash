// ================================================================
//  «Как оплатить» — публичная страница-пояснение способов оплаты.
//  Юлдаш берёт деньги ТОЛЬКО с бизнеса (реклама, подписка, буст, комиссия),
//  попутки между своими — бесплатны. Способы: СБП «на доверии» (реквизиты
//  приходят в момент оплаты) и ЮKassa (когда включат). Честно, двуязычно.
//  Реквизиты не хардкодим: телефон/банк/получатель отдаёт бэкенд при создании
//  платежа (payments.py → payee), а не эта справочная страница.
// ================================================================
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { SubHeader } from "./ConsentsScreen";
import { IconShield, IconPhone, IconReceipt, IconLock, IconWallet, IconChevron } from "../components/Icons";

export default function PaymentInfoScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  return (
    <>
      <SubHeader
        title={appText("Как оплатить", "Нисек түләргә")}
        subtitle={appText(
          "Спокойно и по-соседски — без сюрпризов",
          "Тыныс һәм күршеләрсә — көтөлмәгәнлектәрһеҙ"
        )}
        onBack={() => navigate(-1)}
      />

      {/* Главная мысль: с пассажиров денег не берём */}
      <div className="biz-banner">
        <div className="biz-banner__emoji"><IconShield size={30} /></div>
        <h2>{appText("Попутка — бесплатна", "Юлдаштар — бушлай")}</h2>
        <p>
          {appText(
            "Юлдаш не берёт денег с пассажиров за поездки между своими. Платят только бизнесы — за рекламу, подписку и услуги платформы.",
            "Юлдаш үҙебеҙ араһындағы сәфәрҙәр өсөн юлаусыларҙан аҡса алмай. Тик бизнестар түләй — реклама, яҙылыу һәм платформа хеҙмәттәре өсөн."
          )}
        </p>
      </div>

      {/* Способ 1 — СБП «на доверии» */}
      <div className="pay-way">
        <div className="pay-way__head">
          <span className="pay-way__emoji"><IconPhone size={22} /></span>
          <div>
            <div className="pay-way__title">
              {appText("Перевод по СБП «на доверии»", "СБП аша күсереү «ышаныс менән»")}
            </div>
            <div className="pay-way__badge">
              <span className="badge badge--mint">{appText("Сейчас", "Хәҙер")}</span>
            </div>
          </div>
        </div>
        <p className="pay-way__text">
          {appText(
            "Когда оформляешь оплату (реклама, подписка бизнеса, поднятие поездки), мы показываем номер для перевода через Систему быстрых платежей, банк и имя получателя. Переводишь по номеру — как получим деньги, включаем услугу вручную, по-соседски. Обычно это занимает недолго.",
            "Түләүҙе рәсмиләштергәндә (реклама, бизнес яҙылыуы, сәфәр күтәреү) беҙ Тиҙ түләүҙәр системаһы аша күсереү өсөн номерҙы, банкты һәм алыусы исемен күрһәтәбеҙ. Номерға күсерәһең — аҡса килгәс, хеҙмәтте ҡулдан, күршеләрсә ҡабыҙабыҙ. Ғәҙәттә был оҙаҡ алмай."
          )}
        </p>
      </div>

      {/* Способ 2 — ЮKassa (когда включат) */}
      <div className="pay-way">
        <div className="pay-way__head">
          <span className="pay-way__emoji"><IconReceipt size={22} /></span>
          <div>
            <div className="pay-way__title">
              {appText("Оплата картой (ЮKassa)", "Карта менән түләү (ЮKassa)")}
            </div>
            <div className="pay-way__badge">
              <span className="badge badge--gold">{appText("Скоро", "Тиҙҙән")}</span>
            </div>
          </div>
        </div>
        <p className="pay-way__text">
          {appText(
            "Когда подключим ЮKassa, можно будет платить картой прямо в приложении: откроется защищённая страница оплаты, а после — вернёшься назад, и услуга включится автоматически. Чек придёт на твой номер.",
            "ЮKassa ялғанғас, туранан-тура ҡушымтала карта менән түләргә мөмкин булыр: һаҡланған түләү бите асыла, ә һуңынан кире ҡайтаһың — хеҙмәт үҙе ҡабына. Чек һинең номерыңа килә."
          )}
        </p>
      </div>

      {/* Безопасность */}
      <div className="pay-way">
        <div className="pay-way__head">
          <span className="pay-way__emoji"><IconLock size={22} /></span>
          <div>
            <div className="pay-way__title">
              {appText("Твои данные и поездки под защитой", "Мәғлүмәттәрең — һаҡ аҫтында")}
            </div>
          </div>
        </div>
        <p className="pay-way__text">
          {appText(
            "Мы не храним данные твоей карты — оплата идёт через банк или ЮKassa. Телефон нужен только для чека и связи по оплате. Если что-то не сошлось — напиши в поддержку, разберёмся по-человечески.",
            "Беҙ карта мәғлүмәтеңде һаҡламайбыҙ — түләү банк йәки ЮKassa аша бара. Телефон тик чек һәм түләү буйынса бәйләнеш өсөн кәрәк. Берәй нәмә тура килмәһә — ярҙамға яҙ, кешеләрсә хәл итәбеҙ."
          )}
        </p>
      </div>

      {/* Вход на «Честно о цене» — как устроены деньги (попутка/такси/комиссия) */}
      <div className="list" style={{ marginTop: 16 }}>
        <button
          type="button"
          className="list-row list-row--link"
          onClick={() => navigate("/pricing")}
        >
          <span className="list-row__icon"><IconWallet size={22} /></span>
          <div className="list-row__main">
            <div className="list-row__title">{appText("Честно о цене", "Хаҡ тураһында асыҡтан")}</div>
            <div className="list-row__sub">
              {appText(
                "Попутка бесплатна, тариф такси и комиссия — без мелкого шрифта",
                "Юлдаш бушлай, такси тарифы һәм комиссия — ваҡ хәрефһеҙ"
              )}
            </div>
          </div>
          <span className="list-row__chev"><IconChevron size={20} /></span>
        </button>
      </div>

      <p className="receipt__foot">
        {appText(
          "Способ оплаты зависит от настроек сервиса. Реквизиты для перевода всегда показываем прямо перед оплатой.",
          "Түләү ысулы хеҙмәт көйләүенә бәйле. Күсереү реквизиттарын һәр ваҡыт түләү алдынан күрһәтәбеҙ."
        )}
      </p>
    </>
  );
}
