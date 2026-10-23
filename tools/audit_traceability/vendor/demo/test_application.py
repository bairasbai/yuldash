"""Real local test executions for a tiny synthetic demo, not APK evidence."""

import unittest

from demo.application import Booking, Favorites


class DemoFavoritesTest(unittest.TestCase):
    def test_save_and_repeat_are_idempotent(self):
        favorites = Favorites()
        self.assertFalse(favorites.contains("DEMO-RIDE-1"))
        favorites.save("DEMO-RIDE-1")
        favorites.save("DEMO-RIDE-1")
        self.assertTrue(favorites.contains("DEMO-RIDE-1"))
        self.assertEqual(favorites.count(), 1)


class DemoBookingTest(unittest.TestCase):
    def test_reservation_reduces_seats_without_duplicate_or_overbooking(self):
        booking = Booking(1)
        self.assertTrue(booking.reserve("DEMO-PASSENGER-1"))
        self.assertFalse(booking.reserve("DEMO-PASSENGER-1"))
        self.assertEqual(booking.available, 0)
        with self.assertRaises(ValueError):
            booking.reserve("DEMO-PASSENGER-2")
        self.assertEqual(booking.available, 0)


if __name__ == "__main__":
    unittest.main()
