// GSM 03.38 default alphabet; extension characters cost two septets (escape + char).
const GSM_BASIC =
  "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
const GSM_EXTENSION = "^{}\\[~]|€\f";

export type SmsParts = {
  encoding: "GSM_7" | "UCS_2";
  /** Message parts the carrier bills for. */
  parts: number;
  /** Characters (septets or UTF-16 units) left in the last part. */
  remaining: number;
};

/**
 * How many parts the phone will send for {@code text}: 160/153 septets per part in GSM-7, 70/67 UTF-16
 * units in UCS-2 (any character outside GSM-7 switches the whole message). An empty text is 0 parts.
 */
export function smsParts(text: string): SmsParts {
  let septets = 0;
  let gsm = true;
  for (const char of text) {
    if (GSM_BASIC.includes(char)) septets += 1;
    else if (GSM_EXTENSION.includes(char)) septets += 2;
    else {
      gsm = false;
      break;
    }
  }
  if (gsm) return split("GSM_7", septets, 160, 153);
  return split("UCS_2", text.length, 70, 67);
}

function split(encoding: SmsParts["encoding"], units: number, single: number, multi: number): SmsParts {
  if (units === 0) return { encoding, parts: 0, remaining: single };
  if (units <= single) return { encoding, parts: 1, remaining: single - units };
  const parts = Math.ceil(units / multi);
  return { encoding, parts, remaining: parts * multi - units };
}
