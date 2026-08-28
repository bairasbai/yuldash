package com.yuldash.app

import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Машины на карте заказа: группировка при отдалении и плавный ход между обновлениями.
 *
 * Проверяем чистыми функциями, а не на карте: восемь машин в одном районе на эмуляторе
 * не поставить (каждая требует своего одобренного водителя), а логика здесь вся в двух
 * функциях без Android.
 */
class MapCarsGroupingTest {

    @Test
    fun `близкие машины собираются в одну метку, дальние остаются врозь`() {
        // Три машины в одном квартале и одна на другом конце города.
        val points = listOf(
            Point(52.5900, 58.3100),
            Point(52.5902, 58.3103),
            Point(52.5901, 58.3101),
            Point(52.7100, 58.6600),   // Сибай — далеко
        )
        // Клетка ~0.01 градуса: квартал внутрь попадает целиком, соседний город — нет.
        val groups = groupPoints(points, 0.01)

        assertEquals("должно остаться две метки", 2, groups.size)
        assertTrue("в одной метке три машины", groups.any { it.second == 3 })
        assertTrue("дальняя осталась одна", groups.any { it.second == 1 })
    }

    @Test
    fun `на близком масштабе не склеиваем ничего`() {
        val points = listOf(Point(52.5900, 58.3100), Point(52.5902, 58.3103))
        // Клетка меньше расстояния между машинами — видно каждую.
        val groups = groupPoints(points, 0.00001)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.second == 1 })
    }

    @Test
    fun `метка группы встаёт в середину, а не в угол клетки`() {
        val groups = groupPoints(listOf(Point(52.0, 58.0), Point(52.02, 58.02)), 1.0)
        assertEquals(1, groups.size)
        val (center, count) = groups.first()
        assertEquals(2, count)
        // Середина между точками, иначе кружок повиснет рядом с машинами, которые изображает.
        assertEquals(52.01, center.latitude, 1e-6)
        assertEquals(58.01, center.longitude, 1e-6)
    }

    @Test
    fun `машина продолжает путь от своего прошлого места`() {
        // У машин нет идентификатора (сервер отдаёт только координаты, чтобы нельзя было
        // следить за водителем), поэтому «та же машина» определяется по близости.
        val prev = listOf(Point(52.5900, 58.3100), Point(52.6000, 58.3200))
        val target = listOf(Point(52.6001, 58.3201), Point(52.5901, 58.3101))

        val from = matchByProximity(prev, target)

        // Первой новой точке (она рядом со второй старой) соответствует вторая старая.
        assertEquals(52.6000, from[0].latitude, 1e-6)
        assertEquals(52.5900, from[1].latitude, 1e-6)
    }

    @Test
    fun `далёкая новая машина не тянется через полгорода`() {
        // Появилась машина в другом районе — это не «уехала прежняя», а новая вышла на линию.
        // Тянуть её через весь город было бы враньём: на карте поехала бы машина, которой нет.
        val prev = listOf(Point(52.5900, 58.3100))
        val target = listOf(Point(52.7100, 58.6600))

        val from = matchByProximity(prev, target)

        assertEquals("ставим сразу на место", 52.7100, from[0].latitude, 1e-6)
    }

    @Test
    fun `пустые списки не ломают сопоставление`() {
        assertTrue(matchByProximity(emptyList(), listOf(Point(52.0, 58.0))).isEmpty())
        assertTrue(matchByProximity(listOf(Point(52.0, 58.0)), emptyList()).isEmpty())
        assertTrue(groupPoints(emptyList(), 0.01).isEmpty())
    }
}
