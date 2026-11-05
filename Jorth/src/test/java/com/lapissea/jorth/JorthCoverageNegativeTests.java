package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalClassState;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static com.lapissea.jorth.TestUtils.autoName;
import static com.lapissea.jorth.TestUtils.generateAndLoadInstanceSimple;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative/error-mode coverage (MT-29, MT-30) for the Jorth DSL, plus the MT-34
 * dead-code and unused-fixture audit (recorded as comments only, no executable test).
 * <p>
 * Negative tests verify build-time validation failures.
 */
public class JorthCoverageNegativeTests{
	
	// ================================================================== MT-34 AUDIT (comments only)
	//
	// Dead code — verified by grep across the whole repo (Jorth main + test, and all
	// other modules); each item has zero references outside its own defining file:
	//
	// 1. Operation (lang/type/Operation.java)            -> DELETE
	//    Symbolic-operator KeyedEnum (==, !=, +, ...); no DSL method takes an operator
	//    (arithmetic/bitwise entries take int/double directly), so there is no entry
	//    point to wire it into.
	// 2. FnArgs + ArgInfo (FnArgs.java, ArgInfo.java)    -> DELETE
	//    Argument-info records; call arguments are gathered at build time via CodeArg
	//    lambdas + readCallStack, never via these types.
	// 3. AnnGen (lang/type/AnnGen.java)                  -> DELETE
	//    Annotation-generation record; annotation emission goes through
	//    AnnotationContainer/AnnotationDefinition only.
	// 4. BranchPoint.addOutgoing (BranchPoint.java:22)   -> DELETE (together with the
	//    unused `outgoing` list it appends to)
	//    validateMerge and getOutgoingTypeStack read `ingoing` exclusively.
	// 5. CodeBlock.clone() (CodeBlock.java:513)          -> DELETE
	//    protected, no subclass (no `extends CodeBlock` in the repo) and no call site.
	//
	// Unused fixtures — verified by grep in the test tree; each is referenced only by
	// its own file:
	//
	// 1. WTFIsMyBytecode -> WIRE (preferred): javac "known-good" reference for a
	//    bytecode-comparison test (generate the DSL equivalent of ayy/l2b/i2b/shift and
	//    diff normalized ASM output against the compiled fixture). l2b/i2b are the only
	//    reference for the PrimitiveCastOp emitToTruthy path. DELETE if bytecode diffing
	//    stays out of scope.
	// 2. WtfIsMyEnum     -> WIRE: javac reference for a 2-constant enum (the
	//    JorthTests.simpleEnum shape) in a bytecode-comparison test. NOTE: the string
	//    "com.lapissea.jorth.WtfIsMyEnumAAA" at JorthTests.java:640 is a generated class
	//    name, not a use of this fixture. DELETE otherwise.
	// 3. EnumClass       -> WIRE (NOT yet wired by any coverage file): the fixture class
	//    itself is unreferenced; JorthCoverageStoreArithTests.mt10 duplicates its shape
	//    (FOO(69,"foo :D"), BAR(420,"bar :3")) inline. Use EnumClass's constants as the
	//    behavioral reference in that test.
	// 4. SafeClass       -> WIRE (NOT yet wired by any coverage file): reference for
	//    final-class/private-constructor behavior pins (B03/B08 area). DELETE if those
	//    pins are not added.
	
	// ------------------------------------------------------------------ helpers
	
	/**
	 * Runs a full build (+load) and returns the first throwable, so tests can pin the exact type.
	 */
	private static Throwable buildCapturing(UnsafeConsumer<ClassDefinition, MalformedJorth> generator){
		try{
			generateAndLoadInstanceSimple(autoName(), generator);
		}catch(Throwable e){
			return e;
		}
		throw new AssertionError("Expected the build to throw, but it succeeded");
	}
	
	// ------------------------------------------------------------------ MT-29
	
	// MT-29 — duplicate local name: var(int,"x") twice on the same block
	@Test
	void mt29_duplicateLocalName() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .body().var(int.class, "x").var(int.class, "x"));
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Duplicated localValue: x");
	}
	
	// MT-29 — get("this") inside a static method
	@Test
	void mt29_thisInStaticFunction() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .body().get("this"));
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Cannot use 'this' from a static function");
	}
	
	// MT-29 — duplicate elseRun on the same conditional
	@Test
	void mt29_duplicateElseRun() throws Exception{
		var thrown = buildCapturing(cd -> {
			var body = cd.function("test").staticAcc().arg(int.class, "a").returns(int.class).body();
			body.get("a").val(3)
			    .ifEquality(c -> c.val(1))
			    .elseRun(c -> c.val(2))
			    .elseRun(c -> c.val(3));
		});
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Duplicate else call");
	}
	
	// MT-29 — elseRun after a non-conditional instruction
	@Test
	void mt29_elseRunAfterNonConditional() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc().returns(int.class)
		                                    .body().val(1).elseRun(c -> c.val(2)));
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Must be run after a conditional operation");
	}
	
	// MT-29 — box() on a reference-type stack top
	@Test
	void mt29_boxNonPrimitive() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .arg(String.class, "s").returns(Integer.class)
		                                    .body().get("s").box());
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Cannot box non-primitive");
	}
	
	// MT-29 — unbox() on an int stack top
	@Test
	void mt29_unboxNonWrapper() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .arg(int.class, "x").returns(Integer.class)
		                                    .body().get("x").unbox());
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Cannot unbox type");
	}
	
	// MT-29 — val(Object) with an unsupported runtime type
	@Test
	void mt29_valInvalidObject() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .returns(Object.class)
		                                    .body().val((Object)new Object()));
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Invalid value");
	}
	
	// MT-29 — ifEquality with fewer than 2 elements on the stack
	@Test
	void mt29_ifEqualityInsufficientStack() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .arg(int.class, "a").returns(int.class)
		                                    .body().get("a").ifEquality(c -> c.val(1).returnOp()));
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Required at least 2");
	}
	
	// ------------------------------------------------------------------ MT-30
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Failed to return on .*")
	void mt30_nonVoidMethodEmptyStack() throws Exception{
		generateAndLoadInstanceSimple(autoName(), cd -> cd.function("test").staticAcc().returns(int.class).body());
	}
	
	// MT-30 — throwOp() with an empty stack: rejected at build time with the checked exception
	@Test
	void mt30_throwOpEmptyStack() throws Exception{
		var thrown = buildCapturing(cd -> cd.function("test").staticAcc()
		                                    .body().throwOp());
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Required at least 1");
	}
	
	// MT-30 — building further insns after a returnOp inside the true branch, no-else
	// shape (the no-else extension of bothBranchesTerminateThenUnreachableAccessThrows):
	// the parent is NOT marked terminated, because ConditionalJump.terminates() requires
	// a non-null false branch (Insn.java:549-551). So the trailing returnOp passes
	// preInsn/mergeBranch and fails on the empty false-path stack instead of with
	// "block has terminated".
	@Test
	void mt30_branchTerminatesNoElseThenReturnThrows() throws Exception{
		var thrown = buildCapturing(cd -> {
			var body = cd.function("test").staticAcc().arg(int.class, "a").returns(int.class).body();
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1);
				    code.returnOp();
			    });
			body.returnOp();
		});
		assertThat(thrown).isInstanceOf(MalformedJorth.class)
		                  .hasMessageContaining("Required at least 1");
	}
	
	// ------------------------------------------------------------------ argument slot limit
	
	private static FunctionDefinition slotArgs(FunctionDefinition fn, int longs, int ints){
		for(int i = 0; i<longs; i++) fn.arg(long.class, "l" + i);
		for(int i = 0; i<ints; i++) fn.arg(int.class, "i" + i);
		return fn;
	}
	
	@Test
	void argSlotsAtLimitLoad() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			// 127*2 + 1 = 255
			slotArgs(cd.function("wide").staticAcc(), 127, 1).body();
			// this + 2*2 + 250 = 255
			slotArgs(cd.instanceInit(), 2, 250).body().callSuper(e -> { });
		});
		assertThat(cls.getDeclaredMethods()).anyMatch(m -> m.getName().equals("wide") && m.getParameterCount() == 128);
		assertThat(cls.getDeclaredConstructors()).anyMatch(c -> c.getParameterCount() == 252);
	}
	
	private static final String OVER_BY_ONE = ".*argument slots: 256 .*";
	
	private static FunctionDefinition function(){
		return new ClassDefinition(null).function("test");
	}
	
	@Test(expectedExceptions = IllegalClassState.class, expectedExceptionsMessageRegExp = OVER_BY_ONE)
	void instanceArgOverLimit(){
		// this + 254 = 255
		var fn = slotArgs(function(), 0, 254);
		fn.arg(int.class, "over");
	}
	
	@Test(expectedExceptions = IllegalClassState.class, expectedExceptionsMessageRegExp = OVER_BY_ONE)
	void staticArgOverLimit(){
		var fn = slotArgs(function().staticAcc(), 0, 255);
		fn.arg(int.class, "over");
	}
	
	@Test(expectedExceptions = IllegalClassState.class, expectedExceptionsMessageRegExp = OVER_BY_ONE)
	void wideArgOverLimit(){
		// 127*2 = 254, a long needs the 2 slots that only 1 is left for
		var fn = slotArgs(function().staticAcc(), 127, 0);
		fn.arg(long.class, "over");
	}
	
	@Test(expectedExceptions = IllegalClassState.class, expectedExceptionsMessageRegExp = OVER_BY_ONE)
	void leavingStaticOverLimit(){
		var fn = slotArgs(function().staticAcc(), 0, 255);
		fn.access(AccessSet.DEFAULT);
	}
	
	@Test
	void staticAccFreesTheThisSlot() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var fn = slotArgs(cd.function("test"), 0, 254).staticAcc();
			fn.arg(int.class, "freed");
			fn.body();
		});
		assertThat(cls.getDeclaredMethods()).anyMatch(m -> m.getParameterCount() == 255);
	}
	
	private static ClassDefinition enumClass() throws MalformedJorth{
		return new ClassDefinition(null).name(ClassName.dotted("test.SlotEnum")).type(ClassType.ENUM);
	}
	
	@Test
	void enumConstructorAtLimitLoads() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.type(ClassType.ENUM);
			// this + name + ordinal + 252 = 255
			slotArgs(cd.instanceInit(), 0, 252).body();
			cd.enumConstant("A", c -> {
				for(int i = 0; i<252; i++) c.val(i);
			});
		});
		assertThat(cls.getEnumConstants()).hasSize(1);
	}
	
	@Test(expectedExceptions = IllegalClassState.class, expectedExceptionsMessageRegExp = OVER_BY_ONE)
	void enumConstructorArgOverLimit() throws Exception{
		var fn = slotArgs(enumClass().instanceInit(), 0, 252);
		fn.arg(int.class, "over");
	}
	
	@Test(expectedExceptions = IllegalClassState.class, expectedExceptionsMessageRegExp = OVER_BY_ONE)
	void becomingEnumOverLimit() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.SlotEnum"));
		// this + 253 = 254, the hidden name and ordinal add 2
		slotArgs(cd.instanceInit(), 0, 253);
		cd.type(ClassType.ENUM);
	}
}
