package com.yuldash.app

/** Поездка с сервера → UI-модель (для карточек/брони). Один шов RideDto→Ride. */
internal fun com.yuldash.app.data.RideDto.toUiRide(): Ride = Ride(
    id = id.toString(), category = category,
    from = fromCity,
    to = toCity,
    time = formatDepart(departAt),
    driverId = driverId,
    driver = driverName,   // пусто оставляем; двуязычный дефолт «Водитель»/«Йөрөтөүсе» — на слое отрисовки через appText
    driverAvatar = driverAvatar,
    driverOnline = driverOnline,
    driverTrips = driverTrips,
    driverSince = driverSince,
    driverIsWoman = driverIsWoman,
    driverPayMethods = driverPayMethods,
    car = driverCar,
    price = price,
    seats = seatsLeft,
    seatsTotal = seatsTotal,
    rating = driverRating,
    verified = driverVerified,
    boosted = boosted,          // было хардкод false → поднятые (Boost) поездки не подсвечивались на карте/в кабинете водителя
    petsAllowed = petsAllowed,
    childSeat = childSeat,
    womenOnly = womenOnly,
    smoking = smoking,
    baggage = baggage,
    airConditioner = airConditioner,
    quiet = quiet,
    noMinors = noMinors, backMaxTwo = backMaxTwo, frontSeatAvailable = frontSeatAvailable, frontSeatTaken = frontSeatTaken,
    waypoints = waypoints, matchedStop = matchedStop, matchedFromStart = matchedFromStart, matchedDropoffStop = matchedDropoffStop, matchedToEnd = matchedToEnd,
    pickup = pickup,
    pickupLat = pickupLat,
    pickupLng = pickupLng,
    receiverName = receiverName,
    parcelSize = parcelSize,
    // RS-02 А/Б: версия условий, места по участкам, цены остановок и цена участка.
    termsVersion = termsVersion, seatsByLeg = seatsByLeg, seatsOnSegment = seatsOnSegment, seatsBooked = seatsBooked,
    stopPrices = stopPrices.map { StopPrice(stop = it.stop, price = it.price) }, segmentPrice = segmentPrice,
)
