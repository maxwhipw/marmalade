// Minimal typings for qrcode-terminal (MIT, ships no types).
declare module "qrcode-terminal" {
  const qrcode: {
    generate(input: string, opts?: { small?: boolean }, cb?: (art: string) => void): void;
  };
  export default qrcode;
}
