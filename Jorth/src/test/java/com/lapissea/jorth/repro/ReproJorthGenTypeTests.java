package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * BUG-39 (jorth-gentype): the Jorth type simulation does not accept Java's implicit
 * conversions, because {@code GenericType.instanceOf} treats "same base type or not"
 * as the whole compatibility check.
 *
 * What the code is supposed to do:
 *   Jorth simulates the JVM/javac type rules while building a class. A call site is
 *   checked in {@code InvokeOp.simulate}
 *   (Jorth/src/main/java/com/lapissea/jorth/Insn.java:741-743) and a return in
 *   {@code ReturnOp.simulate} (Insn.java:351-357); both delegate to
 *   {@code GenericType.instanceOf}. The JVM silently widens int -> long at calls and
 *   returns (javac emits an I2L) and silently boxes int -> Integer (javac emits
 *   Integer.valueOf), so Jorth must accept bodies whose stack holds an int where a
 *   long / Integer parameter (or return type) is declared.
 *
 * What it actually does:
 *   {@code GenericType.instanceOf}
 *   (Jorth/src/main/java/com/lapissea/jorth/lang/type/GenericType.java:196-202) does:
 *       var thisInfo = this.getPrimitiveType().orElse(null);   // e.g. INT for int
 *       var thatInfo = right.getPrimitiveType().orElse(null);  // e.g. LONG / null
 *       if(thisInfo != thatInfo) return false;                 // line 200
 *   so (a) two DIFFERENT primitives (int vs long) are incompatible -> no widening, and
 *   (b) a primitive vs its boxed object type (int vs Integer) are incompatible -> no
 *   boxing/unboxing. Any such legal construction throws MalformedJorth at codegen time.
 *
 * Why the tests fail:
 *   Each test builds the Java-legal construct (e.g. `static long m(){ return 5; }` or
 *   a call `takeLong(5)` / `takeInteger(5)`). The expected (correct) behavior is that
 *   codegen succeeds; with the bug present, the simulation throws MalformedJorth
 *   ("Method returns long but int is on stack" / "Argument 0 in ...#takeLong is long
 *   but got int" / "... is Integer but got int") and the test fails with it.
 */
public class ReproJorthGenTypeTests{

    private static final String RETURN_WIDEN = "repro.jorth.gentype.ReturnWiden";
    private static final String CALL_WIDEN   = "repro.jorth.gentype.CallWiden";
    private static final String CALL_BOXING  = "repro.jorth.gentype.CallBoxing";

    @Test
    public void returnWidening_intToLong_isAccepted() throws ReflectiveOperationException{
        // Legal Java source (the JVM widens int -> long, javac emits I2L):
        //     public static long m(){ return 5; }
        try{
            var cd = new ClassDefinition(null);
            cd.name(ClassName.dotted(RETURN_WIDEN));
            var fn = cd.function("m").staticAcc().returns(long.class);
            fn.body()
              .val(5)     // pushes an INT onto the simulated stack
              .returnOp();// BUG trigger: ReturnOp.simulate (Insn.java:355) checks
                          // int.instanceOf(long) -> GenericType.java:200 returns false
                          // for different primitive BaseTypes -> MalformedJorth
            Class<?> cls = load(cd, RETURN_WIDEN);
            assertEquals((long) cls.getMethod("m").invoke(null), 5L);
        }catch(MalformedJorth e){
            // BUG: this exception is the reported defect - the simulation rejects a
            // widening (int -> long) that the JVM silently performs.
            fail("Jorth simulation rejected legal int->long widening on return: "
                 + e.getMessage()
                 + " (GenericType.instanceOf, GenericType.java:196-202, has no widening)");
        }
    }

    @Test
    public void callWidening_intToLong_isAccepted() throws ReflectiveOperationException{
        // Legal Java source (the JVM widens int -> long at the call site):
        //     public static long takeLong(long x){ return x; }
        //     public static long use(){ return takeLong(5); }
        try{
            var cd = new ClassDefinition(null);
            cd.name(ClassName.dotted(CALL_WIDEN));

            var takeLong = cd.function("takeLong").staticAcc()
                             .arg(long.class, "x").returns(long.class);
            takeLong.body().get("x").returnOp();

            var use = cd.function("use").staticAcc().returns(long.class);
            use.body()
              .val(5)  // pushes an INT onto the simulated stack
              .call(takeLong); // BUG trigger: InvokeOp.simulate (Insn.java:741-743)
                               // checks int.instanceOf(long) -> false (GenericType.java:200)
            Class<?> cls = load(cd, CALL_WIDEN);
            assertEquals((long) cls.getMethod("use").invoke(null), 5L);
        }catch(MalformedJorth e){
            // BUG: the simulation rejects passing an int where a long parameter is
            // declared, even though javac/JVM accept the call via an I2L widening.
            fail("Jorth simulation rejected legal int->long widening at call site: "
                 + e.getMessage()
                 + " (GenericType.instanceOf, GenericType.java:196-202, has no widening)");
        }
    }

    @Test
    public void callBoxing_intToInteger_isAccepted() throws ReflectiveOperationException{
        // Legal Java source (javac boxes int -> Integer via Integer.valueOf):
        //     public static Integer takeInteger(Integer x){ return x; }
        //     public static Integer use(){ return takeInteger(5); }
        try{
            var cd = new ClassDefinition(null);
            cd.name(ClassName.dotted(CALL_BOXING));

            var takeInteger = cd.function("takeInteger").staticAcc()
                               .arg(Integer.class, "x").returns(Integer.class);
            takeInteger.body().get("x").returnOp();

            var use = cd.function("use").staticAcc().returns(Integer.class);
            use.body()
              .val(5)   // pushes an INT (primitive) onto the simulated stack
              .call(takeInteger); // BUG trigger: int.instanceOf(Integer) -> false
                                  // (primitive vs object, GenericType.java:200)
            Class<?> cls = load(cd, CALL_BOXING);
            assertEquals((Integer) cls.getMethod("use").invoke(null), 5);
        }catch(MalformedJorth e){
            // BUG: the simulation rejects boxing int -> Integer at the call site,
            // even though javac/JVM accept the call via auto-boxing.
            fail("Jorth simulation rejected legal int->Integer boxing at call site: "
                 + e.getMessage()
                 + " (GenericType.instanceOf, GenericType.java:196-202, has no boxing/unboxing)");
        }
    }

    private static Class<?> load(ClassDefinition cd, String dottedName) throws ReflectiveOperationException, MalformedJorth{
        byte[] bytes = cd.getClassFile();
        var loader = new ClassLoader(ReproJorthGenTypeTests.class.getClassLoader()){
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException{
                if(dottedName.equals(name)){
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };
        return Class.forName(dottedName, true, loader);
    }
}
