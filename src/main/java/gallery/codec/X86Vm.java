package gallery.codec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A small 32-bit x86 interpreter, just big enough to run a Win32 DLL's compute-only code (integer
 * instructions, string ops, a few KERNEL32 imports emulated).  It exists so that Corel's own
 * swi32.dll - found on the user's disc 1 - can be used to decode .WI photographs without any
 * native code or a Windows machine.
 *
 * <p>Memory is one flat byte array; address {@link #BASE} maps to index 0.
 */
final class X86Vm {
    static final int BASE = 0x10000000;
    private static final VarHandle I32 =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle I16 =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    // layout (offsets from BASE)
    private static final int STUB_OFF = 0x200000;
    private static final int TEB_OFF = 0x300000;
    private static final int STACK_LO = 0x400000, STACK_TOP = 0x4F0000;
    private static final int HEAP_OFF = 0x600000;
    private static final int RET_MARK = BASE + STUB_OFF + 0xFF00;

    final byte[] m;
    final int[] r = new int[8];
    int eip;
    boolean cf, zf, sf, of, pf, df;

    private int heapPtr = BASE + HEAP_OFF;
    private final int heapEnd;
    private final Map<Integer, Integer> sizes = new HashMap<>();
    private final Map<Integer, Integer> tls = new HashMap<>();
    private int tlsN;
    private final Map<Integer, String> stubs = new HashMap<>();
    private int imageBase = BASE;
    private int entryPoint;
    private final Map<String, Integer> exports = new HashMap<>();
    long steps;

    X86Vm(byte[] dll, int heapBytes) {
        m = new byte[HEAP_OFF + heapBytes];
        heapEnd = BASE + m.length;
        load(dll);
        w32(BASE + TEB_OFF, -1);
        w32(BASE + TEB_OFF + 0x18, BASE + TEB_OFF);
        w32(BASE + TEB_OFF + 0x30, BASE + TEB_OFF + 0x1000);
    }

    // ------------------------------------------------------------------ PE loading
    private static int le32(byte[] b, int o) { return (int) I32.get(b, o); }
    private static int le16(byte[] b, int o) { return ((short) I16.get(b, o)) & 0xFFFF; }

    private void load(byte[] d) {
        if (d.length < 0x200 || d[0] != 'M' || d[1] != 'Z') throw new IllegalArgumentException("not a PE file");
        int pe = le32(d, 0x3C);
        if (le32(d, pe) != 0x00004550) throw new IllegalArgumentException("bad PE signature");
        int nsec = le16(d, pe + 6), optSize = le16(d, pe + 20), opt = pe + 24;
        if (le16(d, opt) != 0x10B) throw new IllegalArgumentException("not a PE32 image");
        entryPoint = le32(d, opt + 16);
        imageBase = le32(d, opt + 28);
        if (imageBase != BASE) throw new IllegalArgumentException("unexpected image base " + Integer.toHexString(imageBase));
        int sizeOfHeaders = le32(d, opt + 60);
        System.arraycopy(d, 0, m, 0, Math.min(sizeOfHeaders, d.length));
        int sec = opt + optSize;
        for (int i = 0; i < nsec; i++, sec += 40) {
            int vsize = le32(d, sec + 8), va = le32(d, sec + 12), rsize = le32(d, sec + 16), raw = le32(d, sec + 20);
            int n = Math.min(Math.max(rsize, 0), Math.max(vsize, rsize));
            if (raw + n > d.length) n = Math.max(0, d.length - raw);
            if (va + n > STUB_OFF) throw new IllegalArgumentException("image too large");
            System.arraycopy(d, raw, m, va, n);
        }
        // imports
        int dd = opt + 96;
        int impRva = le32(d, dd + 8);
        int next = BASE + STUB_OFF;
        if (impRva != 0) {
            for (int p = impRva; le32(m, p + 12) != 0; p += 20) {
                int oft = le32(m, p), ft = le32(m, p + 16);
                if (oft == 0) oft = ft;
                for (int k = 0;; k += 4) {
                    int thunk = le32(m, oft + k);
                    if (thunk == 0) break;
                    String name;
                    if (thunk < 0) name = "ord" + (thunk & 0xFFFF);
                    else name = cstr(thunk + 2);
                    stubs.put(next, name);
                    w32(BASE + ft + k, next);
                    next += 16;
                }
            }
        }
        // exports
        int expRva = le32(d, dd), expSize = le32(d, dd + 4);
        if (expRva != 0) {
            int nNames = le32(m, expRva + 24), funcs = le32(m, expRva + 28),
                    names = le32(m, expRva + 32), ords = le32(m, expRva + 36);
            for (int i = 0; i < nNames; i++) {
                String nm = cstr(le32(m, names + 4 * i));
                int ord = le16(m, ords + 2 * i);
                exports.put(nm, BASE + le32(m, funcs + 4 * ord));
            }
        }
    }

    private String cstr(int rva) {
        int e = rva;
        while (m[e] != 0) e++;
        return new String(m, rva, e - rva, StandardCharsets.ISO_8859_1);
    }

    int export(String name) {
        Integer a = exports.get(name);
        if (a == null) throw new IllegalArgumentException("export not found: " + name);
        return a;
    }

    // ------------------------------------------------------------------ memory
    int r8(int a) { return m[a - BASE] & 0xFF; }
    int r16(int a) { return ((short) I16.get(m, a - BASE)) & 0xFFFF; }
    int r32(int a) { return (int) I32.get(m, a - BASE); }
    void w8(int a, int v) { m[a - BASE] = (byte) v; }
    void w16(int a, int v) { I16.set(m, a - BASE, (short) v); }
    void w32(int a, int v) { I32.set(m, a - BASE, v); }

    int alloc(int n) {
        n = (n + 15) & ~15;
        int p = heapPtr;
        if ((long) p + n + 16L > heapEnd) throw new OutOfMemoryError("x86 heap exhausted");
        heapPtr += n + 16;
        return p;
    }

    byte[] bytes(int a, int n) {
        byte[] out = new byte[n];
        System.arraycopy(m, a - BASE, out, 0, n);
        return out;
    }

    void put(int a, byte[] src) { System.arraycopy(src, 0, m, a - BASE, src.length); }

    // ------------------------------------------------------------------ running
    /** Calls a stdcall/cdecl function (arguments pushed right-to-left) and returns EAX. */
    int call(int addr, long maxSteps, int... args) {
        int sp = BASE + STACK_TOP;
        for (int i = args.length - 1; i >= 0; i--) { sp -= 4; w32(sp, args[i]); }
        sp -= 4;
        w32(sp, RET_MARK);
        r[4] = sp;
        r[5] = 0;
        eip = addr;
        long limit = steps + maxSteps;
        while (eip != RET_MARK) {
            if (stubs.containsKey(eip)) { stub(); continue; }
            if (steps++ > limit) throw new IllegalStateException("x86 step limit exceeded");
            step();
        }
        return r[0];
    }

    int runDllMain() { return call(BASE + entryPoint, 50_000_000L, BASE, 1, 0); }

    // ------------------------------------------------------------------ KERNEL32 emulation
    private static final Map<String, Integer> NARGS = new HashMap<>();
    static {
        String s = "HeapCreate:3 HeapDestroy:1 HeapAlloc:3 HeapFree:3 HeapReAlloc:4 HeapSize:3 GlobalAlloc:2 GlobalFree:1 "
                + "GlobalSize:1 GlobalLock:1 GlobalUnlock:1 VirtualAlloc:4 VirtualFree:3 TlsAlloc:0 TlsFree:1 TlsSetValue:2 "
                + "TlsGetValue:1 GetLastError:0 SetLastError:1 GetVersion:0 GetCommandLineA:0 GetModuleHandleA:1 "
                + "GetProcAddress:2 GetCurrentThreadId:0 GetCurrentProcess:0 ExitProcess:1 TerminateProcess:2 GetStdHandle:1 "
                + "GetFileType:1 SetHandleCount:1 GetStartupInfoA:1 GetModuleFileNameA:3 GetCPInfo:2 GetACP:0 GetOEMCP:0 "
                + "FreeEnvironmentStringsA:1 FreeEnvironmentStringsW:1 GetEnvironmentStrings:0 GetEnvironmentStringsW:0 "
                + "MultiByteToWideChar:6 WideCharToMultiByte:8 WriteFile:5 InitializeCriticalSection:1 DeleteCriticalSection:1 "
                + "EnterCriticalSection:1 LeaveCriticalSection:1 GetSystemTime:1 GetLocalTime:1 GetTimeZoneInformation:1 "
                + "UnhandledExceptionFilter:1 SetUnhandledExceptionFilter:1 GetStringTypeA:5 GetStringTypeW:4 LCMapStringA:6 "
                + "LCMapStringW:6 RtlUnwind:4 GetTickCount:0 QueryPerformanceCounter:1 IsBadReadPtr:2 IsBadWritePtr:2 "
                + "FlushFileBuffers:1 SetFilePointer:4 CloseHandle:1 GetFileAttributesA:1 LoadLibraryA:1 FreeLibrary:1 "
                + "GetLocaleInfoA:4 SetStdHandle:2 lstrlenA:1 OutputDebugStringA:1 ReadFile:5 CreateFileA:7 "
                + "GetCurrentProcessId:0 SetEndOfFile:1 SetEnvironmentVariableA:2 GetEnvironmentVariableA:3 GetVersionExA:1 "
                + "IsDBCSLeadByte:1 InterlockedIncrement:1 InterlockedDecrement:1 Sleep:1 RaiseException:4 VirtualQuery:3 "
                + "VirtualProtect:4 DisableThreadLibraryCalls:1";
        for (String t : s.split(" ")) {
            int c = t.indexOf(':');
            NARGS.put(t.substring(0, c), Integer.parseInt(t.substring(c + 1)));
        }
    }

    private void stub() {
        String nm = stubs.get(eip);
        int sp = r[4];
        int ret = r32(sp);
        int n = NARGS.getOrDefault(nm, 0);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = r32(sp + 4 + 4 * i);
        int v = 0;
        switch (nm) {
            case "HeapCreate": v = 0x11110000; break;
            case "HeapAlloc": v = alloc(a[2]); sizes.put(v, a[2]); break;
            case "GlobalAlloc": v = alloc(a[1]); sizes.put(v, a[1]); break;
            case "VirtualAlloc": v = alloc(Math.max(a[1], 0x1000)); sizes.put(v, a[1]); break;
            case "HeapReAlloc": {
                v = alloc(a[3]);
                int old = a[2], len = Math.min(sizes.getOrDefault(old, 0), a[3]);
                if (old != 0 && len > 0) System.arraycopy(m, old - BASE, m, v - BASE, len);
                sizes.put(v, a[3]);
                break;
            }
            case "HeapSize": v = sizes.getOrDefault(a[2], 0); break;
            case "GlobalSize": v = sizes.getOrDefault(a[0], 0); break;
            case "HeapFree": case "GlobalFree": case "VirtualFree": case "HeapDestroy": v = 1; break;
            case "TlsAlloc": v = tlsN++; break;
            case "TlsGetValue": v = tls.getOrDefault(a[0], 0); break;
            case "TlsSetValue": tls.put(a[0], a[1]); v = 1; break;
            case "GetVersion": v = 0x0A280105; break;
            case "GetCommandLineA": v = alloc(16); w8(v, 'x'); break;
            case "GetStartupInfoA": java.util.Arrays.fill(m, a[0] - BASE, a[0] - BASE + 68, (byte) 0); break;
            case "GetModuleFileNameA": {
                byte[] s = "swi32.dll\0".getBytes(StandardCharsets.ISO_8859_1);
                put(a[1], s);
                v = 9;
                break;
            }
            case "GetCurrentThreadId": v = 1; break;
            case "GetCurrentProcess": v = -1; break;
            case "GetACP": v = 1252; break;
            case "GetStdHandle": v = 1; break;
            case "GetFileType": v = 2; break;
            case "GetEnvironmentStrings": v = alloc(16); put(v, new byte[] {'X', '=', '1', 0, 0}); break;
            case "GetEnvironmentStringsW":
                v = alloc(32);
                put(v, new byte[] {'X', 0, '=', 0, '1', 0, 0, 0, 0, 0});
                break;
            case "WideCharToMultiByte": {
                int ws = a[2], wl = a[3], out = a[4], ol = a[5];
                byte[] b;
                if (wl == -1) {
                    int k = 0;
                    while (r16(ws + 2 * k) != 0) k++;
                    wl = k + 1;
                }
                b = new byte[wl];
                for (int i = 0; i < wl; i++) b[i] = (byte) r16(ws + 2 * i);
                if (ol == 0) v = b.length;
                else if (ol >= b.length) { put(out, b); v = b.length; }
                break;
            }
            case "MultiByteToWideChar": {
                int sa = a[2], sl = a[3], out = a[4], ol = a[5];
                if (sl == -1) {
                    int k = 0;
                    while (r8(sa + k) != 0) k++;
                    sl = k + 1;
                }
                if (ol == 0) v = sl;
                else if (ol >= sl) {
                    for (int i = 0; i < sl; i++) w16(out + 2 * i, r8(sa + i));
                    v = sl;
                }
                break;
            }
            case "GetModuleHandleA": v = BASE; break;
            case "SetHandleCount": v = a[0]; break;
            case "GetCPInfo":
                java.util.Arrays.fill(m, a[1] - BASE, a[1] - BASE + 20, (byte) 0);
                w32(a[1], 1);
                v = 1;
                break;
            default: break;   // everything else: return 0
        }
        r[0] = v;
        r[4] = sp + 4 + 4 * n;
        eip = ret;
    }

    // ------------------------------------------------------------------ decoding helpers
    private int f8() { return m[(eip++) - BASE] & 0xFF; }
    private int f32() { int v = r32(eip); eip += 4; return v; }
    private int f16() { int v = r16(eip); eip += 2; return v; }

    private int mod, reg, rm, ea, seg;
    private boolean isReg;

    private void modrm() {
        int b = f8();
        mod = b >> 6; reg = (b >> 3) & 7; rm = b & 7;
        if (mod == 3) { isReg = true; return; }
        isReg = false;
        int a;
        if (rm == 4) {
            int sib = f8();
            int sc = sib >> 6, idx = (sib >> 3) & 7, bs = sib & 7;
            a = idx != 4 ? r[idx] << sc : 0;
            if (bs == 5 && mod == 0) a += f32(); else a += r[bs];
        } else if (rm == 5 && mod == 0) {
            a = f32();
        } else {
            a = r[rm];
        }
        if (mod == 1) a += (byte) f8(); else if (mod == 2) a += f32();
        ea = a + seg;
    }

    private int getReg(int i, int size) {
        switch (size) {
            case 4: return r[i];
            case 2: return r[i] & 0xFFFF;
            default: return i < 4 ? r[i] & 0xFF : (r[i - 4] >> 8) & 0xFF;
        }
    }

    private void setReg(int i, int size, int v) {
        switch (size) {
            case 4: r[i] = v; break;
            case 2: r[i] = (r[i] & 0xFFFF0000) | (v & 0xFFFF); break;
            default:
                if (i < 4) r[i] = (r[i] & 0xFFFFFF00) | (v & 0xFF);
                else r[i - 4] = (r[i - 4] & 0xFFFF00FF) | ((v & 0xFF) << 8);
        }
    }

    private int rdE(int size) {
        if (isReg) return getReg(rm, size);
        switch (size) {
            case 4: return r32(ea);
            case 2: return r16(ea);
            default: return r8(ea);
        }
    }

    private void wrE(int size, int v) {
        if (isReg) { setReg(rm, size, v); return; }
        switch (size) {
            case 4: w32(ea, v); break;
            case 2: w16(ea, v); break;
            default: w8(ea, v);
        }
    }

    private int rdMem(int a, int size) { return size == 4 ? r32(a) : size == 2 ? r16(a) : r8(a); }
    private void wrMem(int a, int size, int v) {
        if (size == 4) w32(a, v); else if (size == 2) w16(a, v); else w8(a, v);
    }

    private static long mask(int size) { return size == 4 ? 0xFFFFFFFFL : size == 2 ? 0xFFFFL : 0xFFL; }
    private static int signBit(int size) { return size == 4 ? 0x80000000 : size == 2 ? 0x8000 : 0x80; }

    private void szp(int res, int size) {
        long M = mask(size);
        zf = (res & M) == 0;
        sf = (res & signBit(size)) != 0;
        pf = (Integer.bitCount(res & 0xFF) & 1) == 0;
    }

    /** 0 add,1 or,2 adc,3 sbb,4 and,5 sub,6 xor,7 cmp. */
    private int alu(int op, int a, int b, int size) {
        long M = mask(size);
        int sign = signBit(size);
        int res;
        switch (op) {
            case 0: case 2: {
                long c = (op == 2 && cf) ? 1 : 0;
                long t = (a & M) + (b & M) + c;
                res = (int) t;
                cf = t > M;
                of = ((a ^ res) & (b ^ res) & sign) != 0;
                break;
            }
            case 3: case 5: case 7: {
                long c = (op == 3 && cf) ? 1 : 0;
                long t = (a & M) - (b & M) - c;
                res = (int) t;
                cf = t < 0;
                of = ((a ^ b) & (a ^ res) & sign) != 0;
                break;
            }
            case 1: res = a | b; cf = of = false; break;
            case 4: res = a & b; cf = of = false; break;
            default: res = a ^ b; cf = of = false; break;
        }
        szp(res, size);
        return res;
    }

    private boolean cond(int c) {
        boolean v;
        switch (c >> 1) {
            case 0: v = of; break;
            case 1: v = cf; break;
            case 2: v = zf; break;
            case 3: v = cf || zf; break;
            case 4: v = sf; break;
            case 5: v = pf; break;
            case 6: v = sf != of; break;
            default: v = zf || (sf != of); break;
        }
        return (c & 1) != 0 ? !v : v;
    }

    private void push(int v) { r[4] -= 4; w32(r[4], v); }
    private int pop() { int v = r32(r[4]); r[4] += 4; return v; }

    private int shift(int op, int v, int cnt, int size) {
        cnt &= 31;
        if (cnt == 0) return v;
        int bits = size * 8;
        long M = mask(size);
        long u = v & M;
        int res;
        switch (op) {
            case 4: case 6: // shl
                res = (int) (u << cnt);
                cf = cnt <= bits && ((u >> (bits - cnt)) & 1) != 0;
                of = ((res & signBit(size)) != 0) != cf;
                szp(res, size);
                return res;
            case 5: // shr
                res = (int) (u >>> cnt);
                cf = ((u >>> (cnt - 1)) & 1) != 0;
                of = (v & signBit(size)) != 0;
                szp(res, size);
                return res;
            case 7: { // sar
                long sv = size == 4 ? (long) v : size == 2 ? (long) (short) v : (long) (byte) v;
                res = (int) (sv >> cnt);
                cf = ((sv >> (cnt - 1)) & 1) != 0;
                of = false;
                szp(res, size);
                return res;
            }
            case 0: { // rol
                int c = cnt % bits;
                res = (int) (((u << c) | (u >>> (bits - c))) & M);
                cf = (res & 1) != 0;
                return res;
            }
            case 1: { // ror
                int c = cnt % bits;
                res = (int) (((u >>> c) | (u << (bits - c))) & M);
                cf = (res & signBit(size)) != 0;
                return res;
            }
            default:
                throw new IllegalStateException("unsupported shift/rotate /" + op);
        }
    }

    private void fault(String what, int at) {
        throw new IllegalStateException("x86: " + what + " at " + Integer.toHexString(at));
    }

    // ------------------------------------------------------------------ the interpreter
    private void step() {
        int start = eip;
        int opsz = 4, rep = 0;
        seg = 0;
        int op;
        for (;;) {
            op = f8();
            if (op == 0x66) opsz = 2;
            else if (op == 0xF2) rep = 2;
            else if (op == 0xF3) rep = 1;
            else if (op == 0x64) seg = BASE + TEB_OFF;
            else if (op == 0x2E || op == 0x3E || op == 0x26 || op == 0x36 || op == 0x65) { /* ignore */ }
            else break;
        }
        if (op < 0x40 && (op & 7) < 6) {
            int a = op >> 3;
            switch (op & 7) {
                case 0: modrm(); { int v = alu(a, rdE(1), getReg(reg, 1), 1); if (a != 7) wrE(1, v); } break;
                case 1: modrm(); { int v = alu(a, rdE(opsz), getReg(reg, opsz), opsz); if (a != 7) wrE(opsz, v); } break;
                case 2: modrm(); { int v = alu(a, getReg(reg, 1), rdE(1), 1); if (a != 7) setReg(reg, 1, v); } break;
                case 3: modrm(); { int v = alu(a, getReg(reg, opsz), rdE(opsz), opsz); if (a != 7) setReg(reg, opsz, v); } break;
                case 4: { int v = alu(a, getReg(0, 1), f8(), 1); if (a != 7) setReg(0, 1, v); } break;
                default: {
                    int imm = opsz == 4 ? f32() : f16();
                    int v = alu(a, getReg(0, opsz), imm, opsz);
                    if (a != 7) setReg(0, opsz, v);
                }
            }
            return;
        }
        if (op >= 0x40 && op <= 0x4F) {
            int i = op & 7;
            boolean keep = cf;
            int v = getReg(i, opsz);
            if (op < 0x48) v = alu(0, v, 1, opsz); else v = alu(5, v, 1, opsz);
            cf = keep;
            setReg(i, opsz, v);
            return;
        }
        if (op >= 0x50 && op <= 0x57) { push(r[op & 7]); return; }
        if (op >= 0x58 && op <= 0x5F) { r[op & 7] = pop(); return; }
        if (op >= 0x70 && op <= 0x7F) {
            int d = (byte) f8();
            if (cond(op & 15)) eip += d;
            return;
        }
        if (op >= 0xB0 && op <= 0xB7) { setReg(op & 7, 1, f8()); return; }
        if (op >= 0xB8 && op <= 0xBF) { setReg(op & 7, opsz, opsz == 4 ? f32() : f16()); return; }
        if (op >= 0x91 && op <= 0x97) { int t = r[0]; r[0] = r[op & 7]; r[op & 7] = t; return; }
        switch (op) {
            case 0x0F: twoByte(opsz, start); return;
            case 0x68: push(f32()); return;
            case 0x6A: push((byte) f8()); return;
            case 0x69: case 0x6B: {
                modrm();
                int src = rdE(4);
                int imm = op == 0x69 ? f32() : (byte) f8();
                long p = (long) src * imm;
                int res = (int) p;
                cf = of = p != res;
                szp(res, 4);
                r[reg] = res;
                return;
            }
            case 0x80: case 0x81: case 0x83: {
                modrm();
                int size = op == 0x80 ? 1 : opsz;
                int imm = op == 0x81 ? (opsz == 4 ? f32() : f16()) : (byte) f8();
                int v = alu(reg, rdE(size), imm, size);
                if (reg != 7) wrE(size, v);
                return;
            }
            case 0x84: case 0x85: {
                modrm();
                int size = op == 0x84 ? 1 : opsz;
                alu(4, rdE(size), getReg(reg, size), size);
                return;
            }
            case 0x86: case 0x87: {
                modrm();
                int size = op == 0x86 ? 1 : opsz;
                int a = rdE(size), b = getReg(reg, size);
                wrE(size, b);
                setReg(reg, size, a);
                return;
            }
            case 0x88: modrm(); wrE(1, getReg(reg, 1)); return;
            case 0x89: modrm(); wrE(opsz, getReg(reg, opsz)); return;
            case 0x8A: modrm(); setReg(reg, 1, rdE(1)); return;
            case 0x8B: modrm(); setReg(reg, opsz, rdE(opsz)); return;
            case 0x8D: modrm(); setReg(reg, opsz, ea - seg); return;
            case 0x8F: modrm(); { int v = pop(); wrE(4, v); } return;
            case 0x90: return;
            case 0x98: if (opsz == 4) r[0] = (short) r[0]; else setReg(0, 2, (byte) r[0]); return;
            case 0x99: r[2] = r[0] >> 31; return;
            case 0xA4: case 0xA5: case 0xAA: case 0xAB: case 0xAE: case 0xAF: case 0xA6: case 0xA7:
                stringOp(op, opsz, rep);
                return;
            case 0xA8: alu(4, getReg(0, 1), f8(), 1); return;
            case 0xA9: alu(4, getReg(0, opsz), opsz == 4 ? f32() : f16(), opsz); return;
            case 0xA0: setReg(0, 1, r8(f32() + seg)); return;
            case 0xA1: setReg(0, opsz, rdMem(f32() + seg, opsz)); return;
            case 0xA2: w8(f32() + seg, r[0]); return;
            case 0xA3: wrMem(f32() + seg, opsz, getReg(0, opsz)); return;
            case 0xC0: case 0xC1: case 0xD0: case 0xD1: case 0xD2: case 0xD3: {
                modrm();
                int size = (op & 1) == 0 ? 1 : opsz;
                int cnt = op <= 0xC1 ? f8() : op <= 0xD1 ? 1 : r[1] & 0xFF;
                wrE(size, shift(reg, rdE(size), cnt, size));
                return;
            }
            case 0xC2: { int n = f16(); eip = pop(); r[4] += n; return; }
            case 0xC3: eip = pop(); return;
            case 0xC6: modrm(); wrE(1, f8()); return;
            case 0xC7: modrm(); wrE(opsz, opsz == 4 ? f32() : f16()); return;
            case 0xC9: r[4] = r[5]; r[5] = pop(); return;
            case 0xCC: fault("int3", start); return;
            case 0xE8: { int d = f32(); push(eip); eip += d; return; }
            case 0xE9: { int d = f32(); eip += d; return; }
            case 0xEB: { int d = (byte) f8(); eip += d; return; }
            case 0xF6: case 0xF7: group3(op == 0xF6 ? 1 : opsz); return;
            case 0xFC: df = false; return;
            case 0xFD: df = true; return;
            case 0xF8: cf = false; return;
            case 0xF9: cf = true; return;
            case 0xFE: case 0xFF: group5(op == 0xFE ? 1 : opsz, start); return;
            case 0x9B: return;                                    // wait
            case 0xD8: case 0xD9: case 0xDA: case 0xDB: case 0xDC: case 0xDD: case 0xDE: case 0xDF:
                fpu(op);
                return;
            default:
                fault("unsupported opcode " + Integer.toHexString(op), start);
        }
    }

    /** The x87 unit is ignored: the CRT only touches it for start-up self checks. */
    private void fpu(int op) {
        modrm();
        if (op == 0xD9 && !isReg && reg == 7) w16(ea, 0x027F);     // fnstcw
        else if (op == 0xDF && isReg && reg == 4) setReg(0, 2, 0x4000); // fnstsw ax
    }

    private void stringOp(int op, int opsz, int rep) {
        int size = (op & 1) == 0 ? 1 : opsz;
        int step = df ? -size : size;
        boolean cmpOp = op == 0xAE || op == 0xAF || op == 0xA6 || op == 0xA7;
        for (;;) {
            if (rep != 0 && r[1] == 0) return;
            switch (op) {
                case 0xA4: case 0xA5: wrMem(r[7], size, rdMem(r[6], size)); r[6] += step; r[7] += step; break;
                case 0xAA: case 0xAB: wrMem(r[7], size, getReg(0, size)); r[7] += step; break;
                case 0xAE: case 0xAF: alu(7, getReg(0, size), rdMem(r[7], size), size); r[7] += step; break;
                default: alu(7, rdMem(r[6], size), rdMem(r[7], size), size); r[6] += step; r[7] += step; break;
            }
            if (rep == 0) return;
            r[1]--;
            if (cmpOp) {
                if (rep == 2 && zf) return;      // repne: stop when equal
                if (rep == 1 && !zf) return;     // repe: stop when different
            }
        }
    }

    private void group3(int size) {
        modrm();
        switch (reg) {
            case 0: case 1: alu(4, rdE(size), size == 1 ? f8() : size == 4 ? f32() : f16(), size); return;
            case 2: wrE(size, ~rdE(size)); return;
            case 3: {
                int v = rdE(size);
                int res = alu(5, 0, v, size);
                cf = (v & mask(size)) != 0;
                wrE(size, res);
                return;
            }
            case 4: case 5: {
                if (size == 4) {
                    long a = r[0] & 0xFFFFFFFFL, b = rdE(4) & 0xFFFFFFFFL;
                    long p;
                    if (reg == 4) { p = a * b; r[0] = (int) p; r[2] = (int) (Math.multiplyHigh(a, b)); cf = of = r[2] != 0; }
                    else {
                        long sa = (int) a, sb = (int) rdE(4);
                        p = sa * sb;
                        r[0] = (int) p; r[2] = (int) (p >> 32);
                        cf = of = p != (int) p;
                    }
                } else if (size == 1) {
                    int p = reg == 4 ? (r[0] & 0xFF) * (rdE(1) & 0xFF) : (byte) r[0] * (byte) rdE(1);
                    setReg(0, 2, p);
                    cf = of = reg == 4 ? (p >> 8) != 0 : p != (byte) p;
                } else fault("16-bit mul", eip);
                return;
            }
            case 6: case 7: {
                if (size == 4) {
                    int d = rdE(4);
                    if (d == 0) fault("divide by zero", eip);
                    if (reg == 6) {
                        long n = ((r[2] & 0xFFFFFFFFL) << 32) | (r[0] & 0xFFFFFFFFL);
                        long dd = d & 0xFFFFFFFFL;
                        r[0] = (int) Long.divideUnsigned(n, dd);
                        r[2] = (int) Long.remainderUnsigned(n, dd);
                    } else {
                        long n = ((long) r[2] << 32) | (r[0] & 0xFFFFFFFFL);
                        r[0] = (int) (n / d);
                        r[2] = (int) (n % d);
                    }
                } else fault("8/16-bit div", eip);
                return;
            }
            default: fault("group3", eip);
        }
    }

    private void group5(int size, int start) {
        modrm();
        switch (reg) {
            case 0: case 1: {
                boolean keep = cf;
                int v = alu(reg == 0 ? 0 : 5, rdE(size), 1, size);
                cf = keep;
                wrE(size, v);
                return;
            }
            case 2: { int t = rdE(4); push(eip); eip = t; return; }
            case 4: { eip = rdE(4); return; }
            case 6: push(rdE(4)); return;
            default: fault("group5 /" + reg, start);
        }
    }

    private void twoByte(int opsz, int start) {
        int op = f8();
        if (op >= 0x80 && op <= 0x8F) {
            int d = f32();
            if (cond(op & 15)) eip += d;
            return;
        }
        if (op >= 0x90 && op <= 0x9F) { modrm(); wrE(1, cond(op & 15) ? 1 : 0); return; }
        if (op >= 0x40 && op <= 0x4F) { modrm(); int v = rdE(opsz); if (cond(op & 15)) setReg(reg, opsz, v); return; }
        switch (op) {
            case 0xAF: {
                modrm();
                long p = (long) getReg(reg, 4) * rdE(4);
                int res = (int) p;
                cf = of = p != res;
                szp(res, 4);
                r[reg] = res;
                return;
            }
            case 0xB6: modrm(); setReg(reg, opsz, rdE(1) & 0xFF); return;
            case 0xB7: modrm(); setReg(reg, opsz, rdE(2) & 0xFFFF); return;
            case 0xBE: modrm(); setReg(reg, opsz, (byte) rdE(1)); return;
            case 0xBF: modrm(); setReg(reg, opsz, (short) rdE(2)); return;
            case 0xA2: r[0] = 0; r[3] = r[1] = r[2] = 0; return;   // cpuid: nothing interesting
            default: fault("unsupported opcode 0F " + Integer.toHexString(op), start);
        }
    }
}
