package com.yuldash.app

import androidx.compose.runtime.Composable

/**
 * Экраны кабинета админа, вынесенные из общего выбора экрана в `YuldashApp`.
 *
 * Зачем вынесены. Функция выбора экрана в `YuldashApp` держала все 96 экранов сразу и
 * разрослась настолько, что Android отказывался её ускорять — в логах
 * `Method exceeds compiler instruction limit`. Такой код работает в медленном,
 * непереведённом виде, и это чувствуется на КАЖДОМ переходе между экранами. Больнее
 * всего на слабых телефонах, а в районе таких большинство.
 *
 * Почему именно админские пошли первыми: их двадцать один, а нужно им всего две вещи —
 * «вернуться» и «перейти». Никакого состояния заказа, карты или профиля они не трогают,
 * поэтому переезжают без длинного списка параметров.
 *
 * Добавляешь новый админский экран — добавь его И сюда, И в ветку `when` внутри
 * `YuldashApp`, которая сюда ведёт. Второе Kotlin проверит сам: `when` там обязан
 * покрывать все экраны, и про забытый он скажет на сборке.
 */
@Composable
internal fun AdminNav(screen: Screen, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    when (screen) {
        Screen.AdminCabinet -> AdminCabinetScreen(
            onBack = { onBack() },
            onAdminRequest = { onOpen(Screen.AdminRequest) },
            onAdminResponses = { onOpen(Screen.AdminResponses) },
            onAds = { onOpen(Screen.AdminAds) },
            onDrivers = { onOpen(Screen.AdminDrivers) },
            onReports = { onOpen(Screen.AdminReports) },
            onTextFlags = { onOpen(Screen.AdminTextFlags) },
            onSupportAdmin = { onOpen(Screen.AdminSupport) },
            onPaymentRequests = { onOpen(Screen.AdminPaymentRequests) },
            onTaxi = { onOpen(Screen.AdminTaxi) },
            onWaitlist = { onOpen(Screen.AdminWaitlist) },
            onTaxiPulse = { onOpen(Screen.AdminTaxiPulse) },
            onPartners = { onOpen(Screen.AdminPartners) },
            onModeration = { onOpen(Screen.AdminModeration) },
            onPromoAdmin = { onOpen(Screen.AdminPromo) },
            onParcelsAdmin = { onOpen(Screen.AdminParcels) },
            onCourierAdmin = { onOpen(Screen.AdminCourier) },
            onIncomeCalc = { onOpen(Screen.IncomeCalculator) },
            onSosFeed = { onOpen(Screen.AdminSos) },
            onIncidents = { onOpen(Screen.AdminIncidents) },
            onRatings = { onOpen(Screen.AdminRatings) },
        )
        Screen.AdminDrivers -> AdminDriversScreen(onBack = { onBack() })
        Screen.AdminReports -> AdminReportsScreen(onBack = { onBack() })
        Screen.AdminTextFlags -> AdminTextFlagsScreen(onBack = { onBack() })
        Screen.AdminSupport -> AdminSupportScreen(onBack = { onBack() })
        Screen.AdminPaymentRequests -> AdminPaymentRequestsScreen(onBack = { onBack() })
        Screen.AdminRequest -> AdminRequestScreen(onBack = { onBack() })
        Screen.AdminResponses -> AdminResponsesScreen(onBack = { onBack() })
        Screen.AdminTaxi -> AdminTaxiScreen(onBack = { onBack() })
        Screen.AdminWaitlist -> AdminWaitlistScreen(onBack = { onBack() })
        Screen.AdminTaxiPulse -> AdminTaxiPulseScreen(onBack = { onBack() })
        Screen.AdminSos -> AdminSosScreen(onBack = { onBack() })
        Screen.AdminIncidents -> AdminIncidentsScreen(onBack = { onBack() })
        Screen.AdminRatings -> AdminRatingsScreen(onBack = { onBack() })
        Screen.AdminReviews -> AdminReviewsScreen(onBack = { onBack() })
        Screen.AdminAds -> AdminAdsScreen(onBack = { onBack() })
        Screen.AdminPartners -> AdminPartnersScreen(onBack = { onBack() })
        Screen.AdminModeration -> AdminModerationScreen(
            onBack = { onBack() },
            onOpenPartners = { onOpen(Screen.AdminPartners) },
        )
        Screen.AdminPromo -> AdminPromoScreen(onBack = { onBack() })
        Screen.AdminParcels -> AdminParcelsScreen(onBack = { onBack() })
        Screen.AdminCourier -> AdminCourierScreen(onBack = { onBack() })
        else -> Unit   // сюда не попадаем: снаружи отбор идёт по набору AdminScreens
    }
}
