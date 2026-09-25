import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Static linkage check: does every reference a mod jar makes into its dependencies
 * still resolve against a given set of runtime jars?
 *
 *   java LinkScan <mod.jar> --new <jar|dir>... [--old <jar|dir>...] [--watch pkg/,pkg/]
 *
 * Reports (only for owners inside the watched packages, default = every package the
 * --new jars define):
 *   MISSING-CLASS   a referenced class is gone
 *   MISSING-METHOD  method ref (name+descriptor) no longer resolves  -> NoSuchMethodError
 *   MISSING-FIELD   field ref no longer resolves                     -> NoSuchFieldError
 *   STATIC-MISMATCH static-ness of the target changed                -> IncompatibleClassChangeError
 *   ABSTRACT-UNIMPL a concrete mod class misses an abstract method   -> AbstractMethodError
 *   OVERRIDE-LOST   a mod method overrode a dependency method under --old but overrides nothing under --new
 */
public class LinkScan {
    static final class CInfo {
        String name, sup; String[] itfs; int access;
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Integer> fields = new HashMap<>();
    }

    static Map<String, CInfo> index(List<String> paths) throws IOException {
        Map<String, CInfo> idx = new HashMap<>();
        for (String p : paths) {
            File f = new File(p);
            if (f.isDirectory()) {
                try (var s = Files.walk(f.toPath())) {
                    for (Path c : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                        add(idx, Files.readAllBytes(c));
                    }
                }
            } else {
                try (ZipFile z = new ZipFile(f)) {
                    for (var e : Collections.list(z.entries())) {
                        String n = e.getName();
                        if (!n.endsWith(".class") || n.startsWith("META-INF/versions/")) continue;
                        try (InputStream in = z.getInputStream(e)) { add(idx, in.readAllBytes()); }
                    }
                }
            }
        }
        return idx;
    }

    static void add(Map<String, CInfo> idx, byte[] b) {
        try {
            ClassReader r = new ClassReader(b);
            CInfo ci = new CInfo();
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                public void visit(int v, int acc, String name, String sig, String sup, String[] itfs) {
                    ci.name = name; ci.sup = sup; ci.itfs = itfs == null ? new String[0] : itfs; ci.access = acc;
                }
                public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                    ci.methods.put(n + d, acc); return null;
                }
                public FieldVisitor visitField(int acc, String n, String d, String s, Object v) {
                    ci.fields.put(n + ":" + d, acc); return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (ci.name != null && !idx.containsKey(ci.name)) idx.put(ci.name, ci);
        } catch (RuntimeException ignored) { }
    }

    /** 1 = found (access in out[0]), 0 = definitely absent, -1 = unknown (hierarchy leaves the index). */
    static int resolve(Map<String, CInfo> idx, String owner, String key, boolean field, int[] out) {
        Deque<String> q = new ArrayDeque<>(); Set<String> seen = new HashSet<>();
        q.add(owner); boolean unknown = false;
        while (!q.isEmpty()) {
            String c = q.poll();
            if (c == null || !seen.add(c)) continue;
            if (c.startsWith("java/") || c.startsWith("javax/") || c.startsWith("jdk/") || c.startsWith("sun/")) {
                if (!c.equals("java/lang/Object") || !field) {
                    // resolve against the running JDK
                    try {
                        Class<?> k = Class.forName(c.replace('/', '.'), false, LinkScan.class.getClassLoader());
                        if (jdkHas(k, key, field, out)) return 1;
                    } catch (Throwable t) { unknown = true; }
                }
                continue;
            }
            CInfo ci = idx.get(c);
            if (ci == null) { unknown = true; continue; }
            Integer acc = field ? ci.fields.get(key) : ci.methods.get(key);
            if (acc != null) { out[0] = acc; return 1; }
            if (ci.sup != null) q.add(ci.sup);
            q.addAll(Arrays.asList(ci.itfs));
        }
        return unknown ? -1 : 0;
    }

    static boolean jdkHas(Class<?> k, String key, boolean field, int[] out) {
        for (Class<?> c = k; c != null; c = c.getSuperclass()) {
            if (jdkHasDirect(c, key, field, out)) return true;
            for (Class<?> i : allItfs(c)) if (jdkHasDirect(i, key, field, out)) return true;
        }
        return false;
    }
    static Set<Class<?>> allItfs(Class<?> c) {
        Set<Class<?>> s = new LinkedHashSet<>(); Deque<Class<?>> q = new ArrayDeque<>(Arrays.asList(c.getInterfaces()));
        while (!q.isEmpty()) { Class<?> i = q.poll(); if (s.add(i)) q.addAll(Arrays.asList(i.getInterfaces())); }
        return s;
    }
    static boolean jdkHasDirect(Class<?> c, String key, boolean field, int[] out) {
        if (field) {
            for (var f : c.getDeclaredFields()) if ((f.getName() + ":" + Type.getDescriptor(f.getType())).equals(key)) { out[0] = f.getModifiers(); return true; }
        } else {
            for (var m : c.getDeclaredMethods()) if ((m.getName() + Type.getMethodDescriptor(m)).equals(key)) { out[0] = m.getModifiers(); return true; }
            for (var m : c.getDeclaredConstructors()) if (("<init>" + Type.getConstructorDescriptor(m)).equals(key)) { out[0] = m.getModifiers(); return true; }
        }
        return false;
    }

    public static void main(String[] a) throws Exception {
        String mod = a[0];
        List<String> newP = new ArrayList<>(), oldP = new ArrayList<>(); List<String> watch = new ArrayList<>();
        List<String> cur = null;
        for (int i = 1; i < a.length; i++) {
            switch (a[i]) {
                case "--new" -> cur = newP;
                case "--old" -> cur = oldP;
                case "--watch" -> { watch.addAll(Arrays.asList(a[++i].split(","))); cur = null; }
                default -> cur.add(a[i]);
            }
        }
        Map<String, CInfo> self = index(List.of(mod));
        Map<String, CInfo> nw = index(newP);
        Map<String, CInfo> od = oldP.isEmpty() ? null : index(oldP);
        // the mod's own classes are part of the runtime world too
        for (var e : self.entrySet()) { nw.putIfAbsent(e.getKey(), e.getValue()); if (od != null) od.putIfAbsent(e.getKey(), e.getValue()); }
        Set<String> watchPk = new TreeSet<>(watch);
        if (watchPk.isEmpty()) {
            for (String n : index(newP).keySet()) {
                String[] s = n.split("/");
                if (s.length >= 3) watchPk.add(s[0] + "/" + s[1] + "/" + s[2] + "/");
            }
            watchPk.removeIf(p -> p.startsWith("net/minecraft/") || p.startsWith("com/mojang/"));
        }
        final Set<String> W = watchPk;
        java.util.function.Predicate<String> watched = o -> { for (String p : W) if (o.startsWith(p)) return true; return false; };

        TreeSet<String> problems = new TreeSet<>();
        int[] refs = {0};
        ZipFile z = new ZipFile(mod);
        for (var e : Collections.list(z.entries())) {
            if (!e.getName().endsWith(".class")) continue;
            byte[] b; try (InputStream in = z.getInputStream(e)) { b = in.readAllBytes(); }
            ClassReader r = new ClassReader(b);
            String cls = r.getClassName();
            java.util.function.Consumer<String> needClass = t -> {
                if (t == null) return;
                while (t.startsWith("[")) t = t.substring(1);
                if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
                if (t.length() == 1) return;
                refs[0]++;
                if (watched.test(t) && !nw.containsKey(t)) problems.add("MISSING-CLASS   " + t + "   <- " + cls);
            };
            java.util.function.BiConsumer<Handle, String> handle = (h, where) -> checkMember(nw, watched, problems, refs, h.getOwner(), h.getName(), h.getDesc(),
                    h.getTag() <= Opcodes.H_PUTSTATIC, h.getTag() == Opcodes.H_GETSTATIC || h.getTag() == Opcodes.H_PUTSTATIC || h.getTag() == Opcodes.H_INVOKESTATIC, where);
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                public void visit(int v, int acc, String name, String sig, String sup, String[] itfs) {
                    needClass.accept(sup); if (itfs != null) for (String i : itfs) needClass.accept(i);
                }
                public MethodVisitor visitMethod(int acc, String mn, String md, String s, String[] ex) {
                    String where = cls + "." + mn;
                    return new MethodVisitor(Opcodes.ASM9) {
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            if (owner.startsWith("[")) return;
                            checkMember(nw, watched, problems, refs, owner, name, desc, false, op == Opcodes.INVOKESTATIC, where);
                        }
                        public void visitFieldInsn(int op, String owner, String name, String desc) {
                            checkMember(nw, watched, problems, refs, owner, name, desc, true, op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC, where);
                        }
                        public void visitTypeInsn(int op, String t) { needClass.accept(t); }
                        public void visitTryCatchBlock(Label s, Label e2, Label h, String t) { needClass.accept(t); }
                        public void visitLdcInsn(Object v) {
                            if (v instanceof Type t && t.getSort() == Type.OBJECT) needClass.accept(t.getInternalName());
                            if (v instanceof Handle h) handle.accept(h, where);
                        }
                        public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            for (Object o : args) if (o instanceof Handle h) handle.accept(h, where);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        // overrides / abstract methods
        for (CInfo ci : self.values()) {
            if ((ci.access & Opcodes.ACC_INTERFACE) != 0) continue;
            boolean depSuper = false;
            for (String s : supers(nw, ci.name)) if (!self.containsKey(s) && watched.test(s)) depSuper = true;
            if (!depSuper) continue;
            if ((ci.access & Opcodes.ACC_ABSTRACT) == 0) {
                // every abstract method in the dependency supertypes must have an implementation
                for (String s : supers(nw, ci.name)) {
                    CInfo sc = nw.get(s); if (sc == null || self.containsKey(s)) continue;
                    boolean isItf = (sc.access & Opcodes.ACC_INTERFACE) != 0;
                    for (var m : sc.methods.entrySet()) {
                        if ((m.getValue() & Opcodes.ACC_ABSTRACT) == 0 || (m.getValue() & Opcodes.ACC_STATIC) != 0) continue;
                        if (!implemented(nw, ci.name, m.getKey())) problems.add("ABSTRACT-UNIMPL " + ci.name + " does not implement " + s + "." + m.getKey() + (isItf ? "" : " (abstract class)"));
                    }
                }
            }
            if (od != null) {
                for (var m : ci.methods.entrySet()) {
                    String k = m.getKey(); int acc = m.getValue();
                    if (k.startsWith("<") || (acc & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) != 0) continue;
                    String wasIn = overriddenIn(od, self, ci.name, k);
                    if (wasIn != null && watched.test(wasIn) && overriddenIn(nw, self, ci.name, k) == null)
                        problems.add("OVERRIDE-LOST   " + ci.name + "." + k + " used to override " + wasIn + " - that method is gone/changed, so this code no longer runs");
                }
            }
        }
        System.out.println("# " + new File(mod).getName() + ": " + refs[0] + " references checked, watching " + W);
        if (problems.isEmpty()) System.out.println("OK - no broken links");
        problems.forEach(System.out::println);
        System.exit(problems.isEmpty() ? 0 : 3);
    }

    static List<String> supers(Map<String, CInfo> idx, String c) {
        List<String> out = new ArrayList<>(); Deque<String> q = new ArrayDeque<>(); Set<String> seen = new HashSet<>();
        CInfo ci = idx.get(c); if (ci == null) return out;
        if (ci.sup != null) q.add(ci.sup); q.addAll(Arrays.asList(ci.itfs));
        while (!q.isEmpty()) {
            String s = q.poll(); if (!seen.add(s)) continue; out.add(s);
            CInfo si = idx.get(s); if (si == null) continue;
            if (si.sup != null) q.add(si.sup); q.addAll(Arrays.asList(si.itfs));
        }
        return out;
    }
    static boolean implemented(Map<String, CInfo> idx, String c, String key) {
        for (String s = c; s != null; ) {
            CInfo ci = idx.get(s); if (ci == null) return true; // leaves index (e.g. vanilla) - assume fine
            Integer acc = ci.methods.get(key);
            if (acc != null && (acc & Opcodes.ACC_ABSTRACT) == 0) return true;
            s = ci.sup;
        }
        // default methods in any interface
        for (String s : supers(idx, c)) { CInfo ci = idx.get(s); if (ci == null) continue; Integer acc = ci.methods.get(key);
            if (acc != null && (ci.access & Opcodes.ACC_INTERFACE) != 0 && (acc & Opcodes.ACC_ABSTRACT) == 0) return true; }
        return false;
    }
    static String overriddenIn(Map<String, CInfo> idx, Map<String, CInfo> self, String c, String key) {
        for (String s : supers(idx, c)) { if (self.containsKey(s)) continue; CInfo ci = idx.get(s); if (ci != null && ci.methods.containsKey(key)) {
            int acc = ci.methods.get(key); if ((acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) return s; } }
        return null;
    }

    static void checkMember(Map<String, CInfo> nw, java.util.function.Predicate<String> watched, Set<String> problems, int[] refs,
                            String owner, String name, String desc, boolean field, boolean isStatic, String where) {
        if (!watched.test(owner)) return;
        refs[0]++;
        if (!nw.containsKey(owner)) { problems.add("MISSING-CLASS   " + owner + "   <- " + where); return; }
        int[] acc = {0};
        int r = resolve(nw, owner, field ? name + ":" + desc : name + desc, field, acc);
        if (r == 0) problems.add((field ? "MISSING-FIELD   " : "MISSING-METHOD  ") + owner + "." + name + (field ? ":" : "") + desc + "   <- " + where);
        else if (r == 1 && ((acc[0] & Opcodes.ACC_STATIC) != 0) != isStatic && !name.equals("<init>"))
            problems.add("STATIC-MISMATCH " + owner + "." + name + desc + "   <- " + where);
    }
}
