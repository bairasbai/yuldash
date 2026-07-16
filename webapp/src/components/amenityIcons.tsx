// ================================================================
//  Иконки удобств поездки — единый источник правды (бренд Yu*).
//  Зеркало android: yu_ac / yu_child_seat / yu_luggage / yu_pet /
//  yu_smoke_free / yu_quiet / yu_women_only / yu_accessible /
//  yu_multi_stop. Ключи совпадают с полями заявки/фильтров.
// ================================================================
import {
  YuAc,
  YuChildSeat,
  YuLuggage,
  YuPet,
  YuSmokeFree,
  YuQuiet,
  YuWomenOnly,
  YuAccessible,
  YuMultiStop,
} from "./BrandIcons";

type IconFn = (p: { size?: number; className?: string }) => JSX.Element;

const MAP: Record<string, IconFn> = {
  air_conditioner: YuAc,
  ac: YuAc,
  child_seat: YuChildSeat,
  baggage: YuLuggage,
  luggage: YuLuggage,
  pets_allowed: YuPet,
  pets: YuPet,
  pet: YuPet,
  non_smoking: YuSmokeFree,
  smoke_free: YuSmokeFree,
  quiet: YuQuiet,
  women_only: YuWomenOnly,
  accessible: YuAccessible,
  multi_stop: YuMultiStop,
};

/** Иконка удобства по ключу (или null, если нет соответствия). */
export function AmenityIcon({
  amenity,
  size = 16,
  className,
}: {
  amenity: string;
  size?: number;
  className?: string;
}): JSX.Element | null {
  const Ic = MAP[amenity];
  return Ic ? <Ic size={size} className={className} /> : null;
}
