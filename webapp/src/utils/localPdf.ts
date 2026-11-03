import type { Template } from '@pdfme/common';

export type PdfPageSize = 'A4' | 'A3';

/** Local generation only. Text inputs never become HTML or executable markup. */
export async function createTextPdf(title: string, body: string, font: Uint8Array, pageSize: PdfPageSize = 'A4'): Promise<Uint8Array> {
  if (!title.trim() || !body.trim() || body.length > 100_000) throw new Error('Invalid PDF content');
  const { generate } = await import('./pdfGenerator');
  const width = pageSize === 'A3' ? 297 : 210;
  const height = pageSize === 'A3' ? 420 : 297;
  const template: Template = {
    basePdf: { width, height, padding: [18, 18, 18, 18] },
    schemas: [[
      { name: 'title', type: 'text', position: { x: 18, y: 18 }, width: width - 36, height: 12, fontName: 'ProjectSans', fontSize: 18, overflow: 'expand' },
      { name: 'body', type: 'text', position: { x: 18, y: 38 }, width: width - 36, height: 10, fontName: 'ProjectSans', fontSize: 12, lineHeight: 1.4, overflow: 'expand' },
    ]],
  };
  return generate({ template, inputs: [{ title, body }], options: { font: { ProjectSans: { data: new Uint8Array(font).buffer, fallback: true } } } });
}

let fontPromise: Promise<Uint8Array> | null = null;
async function localFont(): Promise<Uint8Array> {
  if (!fontPromise) {
    fontPromise = fetch(import.meta.env.BASE_URL + 'fonts/pdf/ProjectSans.ttf', { credentials: 'omit', mode: 'same-origin' })
      .then(async response => {
        if (!response.ok) throw new Error('PDF font unavailable');
        return new Uint8Array(await response.arrayBuffer());
      }).catch(error => { fontPromise = null; throw error; });
  }
  return fontPromise;
}

/** A late result is discarded after logout, navigation, refresh or a newer operation. */
export async function downloadTextPdf(options: {
  title: string; body: string; filename: string; pageSize?: PdfPageSize; isCurrent: () => boolean;
}): Promise<boolean> {
  if (!options.isCurrent()) return false;
  const font = await localFont();
  if (!options.isCurrent()) return false;
  const bytes = await createTextPdf(options.title, options.body, font, options.pageSize);
  if (!options.isCurrent()) return false;
  const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'application/pdf' }));
  const link = document.createElement('a');
  try {
    link.href = url;
    link.download = options.filename;
    link.hidden = true;
    document.body.appendChild(link);
    if (!options.isCurrent()) return false;
    link.click();
    return true;
  } finally {
    link.remove();
    // Safari needs the URL to survive the click's download dispatch.
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
