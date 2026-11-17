package gallery.codec;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Decodes Corel .WI photographs.  The codec is a proprietary wavelet scheme whose only
 * implementation is swi32.dll on the Corel Gallery disc; this class runs that DLL's decoder
 * inside {@link X86Vm}, so no Windows, native code or conversion step is needed.
 */
public final class WiDecoder {
    private WiDecoder() {}

    public static boolean looksLikeWi(byte[] d) {
        return d != null && d.length > 8 && d[0] == 'W' && d[1] == 'I';
    }

    public static BufferedImage decode(byte[] wi, byte[] swi32) throws IOException {
        if (!looksLikeWi(wi)) throw new IOException("not a Corel WI file");
        int heap = 64 << 20;
        for (int attempt = 0; ; attempt++) {
            try {
                return run(wi, swi32, heap);
            } catch (OutOfMemoryError e) {
                if (attempt >= 2) throw new IOException("WI image too large to decode", e);
                heap *= 4;
            } catch (RuntimeException e) {
                throw new IOException("WI decode failed: " + e.getMessage(), e);
            }
        }
    }

    private static BufferedImage run(byte[] wi, byte[] swi32, int heap) throws IOException {
        X86Vm vm = new X86Vm(swi32, heap);
        vm.runDllMain();
        int buf = vm.alloc(wi.length + 16);
        vm.put(buf, wi);
        int a1 = vm.alloc(0x80), a2 = vm.alloc(0x80), a3 = vm.alloc(0x80);
        vm.w32(a1, buf);
        vm.w32(a1 + 4, wi.length);
        int rc = vm.call(vm.export("SiDecompress"), 2_000_000_000L, a1, a2, a3);
        int pix = vm.r32(a3), h = vm.r32(a3 + 4), w = vm.r32(a3 + 8), bpp = vm.r32(a3 + 12);
        if (rc != 0 || pix == 0 || bpp != 24 || w <= 0 || h <= 0 || w > 16384 || h > 16384)
            throw new IOException("swi32 returned " + rc + " (" + w + "x" + h + "x" + bpp + ")");
        int stride = (w * 3 + 3) & ~3;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int p = pix + y * stride;
            for (int x = 0; x < w; x++, p += 3)
                row[x] = (vm.r8(p) << 16) | (vm.r8(p + 1) << 8) | vm.r8(p + 2);
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }
}
