/**
 * Keeps a destination price attached to the address for which it was calculated.
 * The component owns rendering; this small coordinator owns only request ordering.
 */
export class LatestDestinationPreview {
  #revision = 0;
  #point = null;
  #samePoint;

  constructor(samePoint) {
    this.#samePoint = samePoint;
  }

  begin(point) {
    this.#point = point;
    return ++this.#revision;
  }

  resolve(revision, quote) {
    if (!this.isCurrent(revision) || !this.#point) return null;
    return { point: this.#point, quote };
  }

  isCurrent(revision) {
    return revision === this.#revision;
  }

  canApply(point, preview) {
    return Boolean(
      this.#point &&
        point &&
        preview &&
        this.#samePoint(point, this.#point) &&
        this.#samePoint(preview.point, this.#point)
    );
  }

  clear() {
    this.#revision++;
    this.#point = null;
  }
}
