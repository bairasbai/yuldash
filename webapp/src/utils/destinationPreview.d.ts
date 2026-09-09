export interface DestinationPreview<Point, Quote> {
  point: Point;
  quote: Quote;
}

export declare class LatestDestinationPreview<Point, Quote> {
  constructor(samePoint: (left: Point, right: Point) => boolean);
  begin(point: Point): number;
  resolve(revision: number, quote: Quote): DestinationPreview<Point, Quote> | null;
  isCurrent(revision: number): boolean;
  canApply(point: Point | null, preview: DestinationPreview<Point, Quote> | null): boolean;
  clear(): void;
}
