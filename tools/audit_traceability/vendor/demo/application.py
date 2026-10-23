"""Tiny SYNTHETIC DEMO implementations, unrelated to Yuldash or audited APKs."""


class Favorites:
    def __init__(self):
        self._saved = set()

    def save(self, ride_id):
        if not ride_id:
            raise ValueError("A ride identifier is required")
        self._saved.add(ride_id)

    def contains(self, ride_id):
        return ride_id in self._saved

    def count(self):
        return len(self._saved)


class Booking:
    def __init__(self, seats):
        if type(seats) is not int or seats < 0:
            raise ValueError("Seats must be a nonnegative integer")
        self.available = seats
        self._passengers = set()

    def reserve(self, passenger_id):
        if not passenger_id:
            raise ValueError("A passenger identifier is required")
        if passenger_id in self._passengers:
            return False
        if self.available == 0:
            raise ValueError("No seats available")
        self._passengers.add(passenger_id)
        self.available -= 1
        return True
