import type { Content, TDocumentDefinitions } from 'pdfmake/interfaces';
import { indigo, ink } from '../../styles/tokens';

export const PDF_COLORS = {
  brand: indigo[600],
  ink: ink[1],
  inkSub: ink[3],
  border: '#e5e7eb',
  headerBg: '#f4f6fb',
} as const;

const REGULAR = 'NotoSansSC-Regular.otf';
const BOLD = 'NotoSansSC-Bold.otf';

async function fetchFontBase64(name: string): Promise<string> {
  const resp = await fetch(`${import.meta.env.BASE_URL}fonts/${name}`);
  if (!resp.ok) throw new Error(`字体加载失败: ${name} (${resp.status})`);
  const buf = new Uint8Array(await resp.arrayBuffer());
  let bin = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < buf.length; i += CHUNK) {
    bin += String.fromCharCode(...buf.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

/** 懒加载 pdfmake + 中文字体并触发下载（与 xlsx 导出同模式，仅点击时拉取）。 */
export async function downloadPdf(def: TDocumentDefinitions, filename: string): Promise<void> {
  const mod = await import('pdfmake/build/pdfmake');
  const pdfMake = mod.default ?? mod;
  const [regular, bold] = await Promise.all([fetchFontBase64(REGULAR), fetchFontBase64(BOLD)]);
  pdfMake.vfs = { [REGULAR]: regular, [BOLD]: bold };
  pdfMake.fonts = {
    NotoSansSC: { normal: REGULAR, bold: BOLD, italics: REGULAR, bolditalics: BOLD },
  };
  pdfMake.createPdf(def).download(filename);
}

/** 文档骨架：默认字体/样式/页脚页码/页边距。 */
export function docSkeleton(title: string, subtitle: string): Pick<TDocumentDefinitions, 'defaultStyle' | 'styles' | 'footer' | 'pageMargins'> {
  return {
    pageMargins: [48, 56, 48, 56],
    defaultStyle: { font: 'NotoSansSC', fontSize: 10, color: PDF_COLORS.ink, lineHeight: 1.35 },
    styles: {
      cover: { fontSize: 26, bold: true, color: PDF_COLORS.brand },
      coverSub: { fontSize: 12, color: PDF_COLORS.inkSub, margin: [0, 8, 0, 0] },
      h2: { fontSize: 15, bold: true, color: PDF_COLORS.brand, margin: [0, 18, 0, 8] },
      th: { bold: true, fontSize: 9, color: PDF_COLORS.inkSub, fillColor: PDF_COLORS.headerBg },
      sub: { fontSize: 9, color: PDF_COLORS.inkSub },
    },
    footer: (currentPage: number, pageCount: number) => ({
      columns: [
        { text: `${title} · ${subtitle}`, style: 'sub', margin: [48, 0, 0, 0] },
        { text: `${currentPage} / ${pageCount}`, style: 'sub', alignment: 'right', margin: [0, 0, 48, 0] },
      ],
      margin: [0, 16, 0, 0],
    }),
  };
}

export function sectionTitle(text: string): Content {
  return { text, style: 'h2' };
}

/** 两列键值表（无边框、斑纹）。 */
export function kvTable(rows: [string, string][]): Content {
  return {
    table: { widths: [140, '*'], body: rows.map(([k, v]) => [{ text: k, bold: true }, { text: v }]) },
    layout: {
      hLineWidth: () => 0,
      vLineWidth: () => 0,
      fillColor: (rowIndex: number) => (rowIndex % 2 === 0 ? '#fafbfd' : null),
      paddingTop: () => 5,
      paddingBottom: () => 5,
    },
    margin: [0, 4, 0, 0],
  };
}
