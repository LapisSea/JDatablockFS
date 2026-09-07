package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeStack;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-38 (jorth-typestack): TypeStack.pop() refuses to consume a value that was pushed on
 * the outer code path, even though peekLast()/requireElements() can see it — an asymmetric
 * dead end that makes it impossible to codegen perfectly valid JVM bytecode.
 *
 * What the code is supposed to do:
 *   A branch (ifTrue/ifFalse/elseRun) creates a child CodeBlock whose TypeStack is built on
 *   top of the outer block's stack (CodeBlock.java:42-43, CodeBlock.createBlockFromHere
 *   CodeBlock.java:122-130). The parent chain is consulted by peekLast()
 *   (TypeStack.java:61-67) and requireElements() (TypeStack.java:56-60), so a branch can
 *   "see" values pushed before the branch. pop() is supposed to be symmetric: consuming
 *   such a value inside a branch is valid JVM bytecode, e.g. the ternary shape
 *   {@code cond ? f(x) : g(x)} where x is already on the operand stack compiles to
 *   {@code aload x; iload cond; ifeq else; ...consume x...; goto end; else: ...consume x...;
 *   end:} which the JVM verifier accepts.
 *
 * What it actually does:
 *   TypeStack.pop() (TypeStack.java:37-43) calls requireElements(1), which PASSES because
 *   it counts the parent chain, and then throws
 *   {@code MalformedJorth("can not pop values outside the code path")} whenever the block's
 *   own (local) portion is empty. So the very first stack-consuming instruction inside any
 *   branch is rejected, even though peekLast/requireElements happily expose the same value.
 *
 * Why this test fails:
 *   Both test methods build exactly that situation — a value pushed on the outer path that
 *   is consumed inside an ifTrue branch. With the current code, pop() throws
 *   MalformedJorth("can not pop values outside the code path") (unit test) / the codegen
 *   chain is rejected with the same exception (codegen test), so both tests fail with an
 *   assertion error wrapping that exception, while the equivalent hand-written bytecode is
 *   perfectly valid JVM code.
 */
public class ReproJorthTypeStackTests{

    private static final String EXPECTED_MESSAGE = "can not pop values outside the code path";

    /**
     * Unit level: pop() must be able to consume what peekLast()/requireElements() can see.
     * A child TypeStack with an empty local portion and a non-empty parent chain is exactly
     * the state a branch block is in right after entering the branch.
     */
    @Test
    public void popConsumesParentChainValue() throws Exception{
        var parent = new TypeStack(null);
        parent.push(GenericType.INT); // value pushed on the outer code path

        var child = new TypeStack(parent); // branch-like child: local portion empty, parent holds the value

        // peekLast() and requireElements() happily see the parent's value ...
        assertThat(child.peekLast())
            .as("peekLast sees the value pushed on the outer path")
            .isEqualTo(GenericType.INT);
        child.requireElements(1); // passes: totalStack().count() == 1 (TypeStack.java:56-60)

        // ... so pop() must be able to consume it. With BUG-38 (TypeStack.java:37-43)
        // pop() throws MalformedJorth("can not pop values outside the code path") instead.
        GenericType popped;
        try{
            popped = child.pop();
        }catch(MalformedJorth e){
            if(!String.valueOf(e.getMessage()).contains(EXPECTED_MESSAGE)){
                throw new AssertionError("pop() failed for an unexpected reason (harness issue?)", e);
            }
            // BUG-38: asymmetric dead end — peekLast() sees the value but pop() refuses it
            throw new AssertionError(
                "BUG-38: TypeStack.pop() refuses to consume a value that peekLast()/requireElements() can see: "
                + e.getMessage()
                + " — but the JVM accepts bytecode where a branch consumes a value pushed before the branch", e);
        }
        assertThat(popped)
            .as("pop consumed the value pushed on the outer path")
            .isEqualTo(GenericType.INT);
    }

    /**
     * Codegen level: a branch consuming a value pushed on the outer path must be accepted.
     * Pattern (ternary-like, with "s" already on the stack before the branch):
     * <pre>
     *   static void consumeIfTrue(String s, boolean cond){
     *       if(cond){ /* consume s *\/ } else { /* consume s *\/ }
     *   }
     * </pre>
     * Equivalent bytecode (valid JVM code):
     * {@code aload s; iload cond; ifeq else; pop; goto end; else: pop; end: return}
     */
    @Test
    public void branchConsumesValuePushedOutside() throws Exception{
        var className = "test.ReproJorthTypeStackBranch";
        byte[] classFile;
        try{
            var cd = new ClassDefinition(null);
            cd.name(ClassName.dotted(className));
            var fn = cd.function("consumeIfTrue").staticAcc()
                       .arg(String.class, "s")
                       .arg(boolean.class, "cond");
            fn.body()
              .get("s")               // push s on the outer code path
              .get("cond")           // push the condition on top
              .ifTrue(c -> c.pop())  // inside the branch: consume s — valid JVM bytecode
              .elseRun(c -> c.pop());
            classFile = cd.getClassFile();
        }catch(MalformedJorth e){
            if(!String.valueOf(e.getMessage()).contains(EXPECTED_MESSAGE)){
                throw new AssertionError("codegen failed for an unexpected reason (harness issue?)", e);
            }
            // BUG-38: the whole codegen is rejected because the branch tries to pop a
            // value that was pushed on the outer path (TypeStack.java:37-43)
            throw new AssertionError(
                "BUG-38: codegen rejected a value pushed on the outer path and consumed inside an ifTrue branch: "
                + e.getMessage()
                + " — but the equivalent bytecode (aload s; iload cond; ifeq else; pop; goto end; else: pop; end: return) is valid JVM code", e);
        }

        // Success path, only reachable once BUG-38 is fixed: the generated class must load and run
        var cls = loadClass(className, classFile);
        var method = cls.getMethod("consumeIfTrue", String.class, boolean.class);
        method.invoke(null, "hello", true);  // both branches must be executable
        method.invoke(null, "hello", false);
    }

    private static Class<?> loadClass(String className, byte[] classFile) throws ClassNotFoundException{
        var loader = new ClassLoader(ReproJorthTypeStackTests.class.getClassLoader()){
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException{
                if(name.equals(className)){
                    return defineClass(name, classFile, 0, classFile.length);
                }
                return super.findClass(name);
            }
        };
        return Class.forName(className, true, loader);
    }
}
