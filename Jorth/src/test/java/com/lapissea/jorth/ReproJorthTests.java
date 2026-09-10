package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.lapissea.jorth.TestUtils.autoName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class ReproJorthTests{
	
	private record Generated(Class<?> cls, byte[] bytes){ }
	
	private static byte[] generate(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws MalformedJorth{
		return generate(className, ReproJorthTests.class.getClassLoader(), generator);
	}
	private static byte[] generate(String className, ClassLoader parent, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws MalformedJorth{
		var cd = new ClassDefinition(parent).name(ClassName.dotted(className));
		generator.accept(cd);
		return cd.getClassFile();
	}
	
	private static Class<?> loadSingleClass(String name, byte[] bytes, ClassLoader parent) throws ClassNotFoundException{
		var loader = new ClassLoader(parent){
			@Override
			protected Class<?> findClass(String cn) throws ClassNotFoundException{
				if(cn.equals(name)) return defineClass(cn, bytes, 0, bytes.length);
				return super.findClass(cn);
			}
		};
		return Class.forName(name, true, loader);
	}
	private static Class<?> loadSingleClass(String name, byte[] bytes) throws ClassNotFoundException{
		return loadSingleClass(name, bytes, ReproJorthTests.class.getClassLoader());
	}
	
	private static Class<?> generateAndLoad(String name, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		return loadSingleClass(name, generate(name, generator));
	}
	private static Generated generateWithBytes(String name, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		return generateWithBytes(name, ReproJorthTests.class.getClassLoader(), generator);
	}
	private static Generated generateWithBytes(String name, ClassLoader parent, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		var bytes = generate(name, parent, generator);
		return new Generated(loadSingleClass(name, bytes, parent), bytes);
	}
	
	// Integer constants
	
	@DataProvider
	Object[][] integerConstants(){
		return new Object[][]{
			{Integer.MIN_VALUE}, {-70000}, {-32769}, {-32768}, {-129}, {-128}, {-2}, {-1},
			{0}, {1}, {2}, {3}, {4}, {5}, {6}, {127}, {128}, {32767}, {32768},
			{40000}, {70000}, {Integer.MAX_VALUE}
		};
	}
	
	@Test(dataProvider = "integerConstants")
	void integerConstantsRoundTrip(int value) throws Exception{
		var cls = generateAndLoad("test.IntegerConstant", cd ->
			                                                  cd.function("value").staticAcc().returns(int.class).body().val(value));
		assertThat(cls.getMethod("value").invoke(null)).isEqualTo(value);
	}
	
	@Test(dataProvider = "integerConstants")
	void incrementsPreserveConstant(int value) throws Exception{
		var cls = generateAndLoad("test.IntegerIncrement", cd ->
			                                                   cd.function("add").staticAcc().arg(int.class, "x").returns(int.class)
			                                                     .body().get("x").add(value));
		assertThat(cls.getMethod("add", int.class).invoke(null, 7)).isEqualTo(7 + value);
	}
	
	// Long constants
	
	@DataProvider
	Object[][] longConstants(){
		return new Object[][]{
			{0L}, {1L}, {-1L}, {2L}, {1L<<32}, {(1L<<32) + 1},
			{-(1L<<32)}, {-(1L<<32) + 1}, {1234567890123L},
			{Long.MIN_VALUE}, {Long.MIN_VALUE + 1}, {Long.MAX_VALUE}
		};
	}
	
	@Test(dataProvider = "longConstants")
	void longConstantsRoundTrip(long value) throws Exception{
		var cls = generateAndLoad("test.LongConstant", cd -> {
			cd.function("value").staticAcc().returns(long.class).body().val(value);
			cd.function("increment").staticAcc().returns(long.class).body().val(value).add(7);
		});
		assertThat(cls.getMethod("value").invoke(null)).isEqualTo(value);
		assertThat(cls.getMethod("increment").invoke(null)).isEqualTo(value + 7);
	}
	
	// Narrow numeric increments
	
	@DataProvider
	Object[][] narrowTypes(){
		return new Object[][]{
			{byte.class, (byte)100},
			{short.class, (short)100},
			{char.class, (char)100}
		};
	}
	
	@Test(dataProvider = "narrowTypes")
	void incrementProducesInt(Class<?> type, Object value) throws Exception{
		var cls = generateAndLoad("test.IncrementResult", cd -> {
			cd.function("direct").staticAcc().arg(type, "value").returns(int.class)
			  .body().get("value").add(200).returnOp();
			cd.function("local").staticAcc().arg(type, "value").returns(int.class)
			  .body().var(int.class, "result").get("value").add(200).set("result").get("result");
			cd.function("zero").staticAcc().arg(type, "value").returns(int.class)
			  .body().get("value").add(0);
		});
		assertThat(cls.getMethod("direct", type).invoke(null, value)).isEqualTo(300);
		assertThat(cls.getMethod("local", type).invoke(null, value)).isEqualTo(300);
		assertThat(cls.getMethod("zero", type).invoke(null, value)).isEqualTo(100);
	}
	
	@Test(dataProvider = "narrowTypes", expectedExceptions = MalformedJorth.class)
	void incrementRequiresCastToNarrowLocal(Class<?> type, Object value) throws Exception{
		generateAndLoad("test.IncrementNarrowStore", cd ->
			                                             cd.function("run").staticAcc().arg(type, "value").body()
			                                               .get("value").add(200).set("value"));
	}
	
	@Test(dataProvider = "narrowTypes")
	void explicitCastAllowsNarrowStore(Class<?> type, Object value) throws Exception{
		var cls = generateAndLoad("test.IncrementCast", cd ->
			                                                cd.function("run").staticAcc().arg(type, "value").returns(type).body()
			                                                  .get("value").add(200).cast(type).set("value").get("value"));
		Object expected;
		if(type == byte.class) expected = (byte)300;
		else if(type == short.class) expected = (short)300;
		else expected = (char)300;
		assertThat(cls.getMethod("run", type).invoke(null, value)).isEqualTo(expected);
	}
	
	// Null values
	
	@Test(dataProvider = "primitives", expectedExceptions = MalformedJorth.class,
	      expectedExceptionsMessageRegExp = "For null constant, the type must be object but is: .*")
	void nullRejectsPrimitiveAndVoidTypes(Class<?> type) throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.InvalidNull"));
		cd.function("run").staticAcc().body().nullVal(type);
	}
	
	@DataProvider
	Object[][] referenceTypes(){
		return new Object[][]{
			{Object.class}, {String.class}, {Runnable.class}, {int[].class},
			{boolean[].class}, {String[].class}, {int[][].class}
		};
	}
	
	@Test(dataProvider = "referenceTypes")
	void nullReferenceLoadsAndReturns(Class<?> type) throws Exception{
		var cls = generateAndLoad("test.ReferenceNull", cd ->
			                                                cd.function("value").staticAcc().returns(type).body().nullVal(type));
		assertThat(cls.getMethod("value").invoke(null)).isNull();
	}
	
	// Array allocation
	
	@DataProvider
	Object[][] arrays(){
		var cases = new ArrayList<Object[]>();
		for(var type : new Class<?>[]{boolean[].class, byte[].class, short[].class, char[].class,
		                              int[].class, long[].class, float[].class, double[].class, String[].class}){
			for(int length : new int[]{0, 4}) cases.add(new Object[]{type, length});
		}
		return cases.toArray(Object[][]::new);
	}
	
	@Test(dataProvider = "arrays")
	void createsArrayWithCorrectTypeAndLength(Class<?> type, int length) throws Exception{
		var cls = generateAndLoad("test.ArrayAllocation", cd ->
			                                                  cd.function("make").staticAcc().returns(type).body().val(length).newObj(type));
		var result = cls.getMethod("make").invoke(null);
		assertThat(result.getClass()).isEqualTo(type);
		assertThat(Array.getLength(result)).isEqualTo(length);
		var expected = Array.newInstance(type.getComponentType(), length);
		for(int i = 0; i<length; i++){
			assertThat(Array.get(result, i)).isEqualTo(Array.get(expected, i));
		}
	}
	
	// Invalid operands
	
	private static CodeBlock body() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.InvalidOperand"));
		return cd.function("run").staticAcc().body();
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot increment .* by int")
	void addIntRejectsReference() throws MalformedJorth{
		body().val("text").add(1);
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot increment .* by double")
	void addDoubleRejectsFloat() throws MalformedJorth{
		body().val(1.5F).add(1.5);
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot bit shift .*")
	void leftShiftRejectsReference() throws MalformedJorth{
		body().val("text").bitShiftLeft(1);
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot bit shift .*")
	void rightShiftRejectsFloat() throws MalformedJorth{
		body().val(1.5F).bitShiftRight(false, 1);
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot bit shift .*")
	void unsignedRightShiftRejectsReference() throws MalformedJorth{
		body().val("text").bitShiftRight(true, 1);
	}
	
	// Operand stack bounds
	
	@DataProvider
	Object[][] outOfBoundsPositions(){
		// stack size is always 1 in these tests, so the last valid index is 0
		return new Object[][]{
			{1},   // one past the last valid index
			{10},  // far beyond the stack
			{-1},  // negative index
		};
	}
	
	@Test(dataProvider = "outOfBoundsPositions",
	      expectedExceptions = MalformedJorth.class,
	      expectedExceptionsMessageRegExp = "peek position .+ is out of bounds for the stack of size 1")
	void peekOutOfBoundsShouldThrowMalformedJorth(int pos) throws MalformedJorth{
		var stack = new TypeStack(null);
		stack.push(GenericType.INT);
		stack.peek(pos);
	}
	
	@Test
	void peekInBoundsWorks() throws MalformedJorth{
		var stack = new TypeStack(null);
		stack.push(GenericType.INT);
		assertThat(stack.peek(0))
			.as("peek(0) on a one-element stack must return the pushed value without throwing")
			.isEqualTo(GenericType.INT);
	}
	
	// Generation errors
	
	public static class NoDefaultConstructor{
		public NoDefaultConstructor(int value){ }
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Failed to return on .*")
	void invalidImplicitReturnThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.MissingReturn"));
		cd.function("missingReturn").returns(int.class).body();
		cd.getClassFile();
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = ".*noSuchMethod.*")
	void missingMethodThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.BadCall"));
		cd.function("badCall").staticAcc().returns(String.class)
		  .body().val("hello").call("noSuchMethod");
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void invalidGeneratedConstructorThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.BadConstructor"));
		cd.extendsType(NoDefaultConstructor.class);
		cd.getClassFile();
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Deferred failure")
	void lazyBlockFailureThrowsMalformedJorth() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reproerrortype.BadLazyBlock"));
		cd.function("run").staticAcc().body().lazyBlock(code -> {
			throw new MalformedJorth("Deferred failure");
		});
		cd.getClassFile();
	}
	
	// Argument resolution
	
	public static class Candidate{
		public Candidate(Object first, int second){ }
	}
	
	public static class Overloaded{
		public Overloaded(Object first, int second)   { }
		public Overloaded(Object first, String second){ }
	}
	
	private static ClassInfo info(Class<?> type){
		var source = TypeSource.of(null, ReproJorthTests.class.getClassLoader());
		return new ClassInfo.OfClass(source, type);
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void matchingObjectDoesNotHideLaterMismatch() throws MalformedJorth{
		info(Candidate.class).getFunction(new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, GenericType.STRING)));
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void matchingObjectDoesNotHideWrongArity() throws MalformedJorth{
		info(Candidate.class).getFunction(new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT)));
	}
	
	@Test
	void matchingConstructorResolves() throws MalformedJorth{
		var signature = new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, GenericType.INT));
		assertThat(info(Candidate.class).getFunction(signature).makeSignature()).isEqualTo(signature);
	}
	
	@Test
	void overloadResolutionChecksArgumentsAfterObject() throws MalformedJorth{
		var info = info(Overloaded.class);
		for(var second : List.of(GenericType.INT, GenericType.STRING)){
			var signature = new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, second));
			assertThat(info.getFunction(signature).makeSignature()).isEqualTo(signature);
		}
	}
	
	// Constructor visibility
	public static class PublicCtor{
		public PublicCtor(){ }
	}
	
	static class ProtectedCtor{
		protected ProtectedCtor(){ }
	}
	
	static class PackageCtor{
		PackageCtor(){ }
	}
	
	static class PrivateCtor{
		private PrivateCtor(){ }
	}
	
	private static Visibility visibilityOf(Class<?> type) throws NoSuchMethodException{
		var source = TypeSource.of(null, type.getClassLoader());
		return new FunctionInfo.OfConstructor(source, type.getDeclaredConstructor()).visibility();
	}
	
	@Test
	void publicConstructorVisibilityShouldBePublic() throws NoSuchMethodException{
		assertThat(visibilityOf(PublicCtor.class)).isEqualTo(Visibility.PUBLIC);
	}
	
	@Test
	void nonPublicConstructorVisibilityCorrect() throws NoSuchMethodException{
		assertThat(visibilityOf(ProtectedCtor.class)).isEqualTo(Visibility.PROTECTED);
		assertThat(visibilityOf(PackageCtor.class)).isEqualTo(Visibility.PACKAGE_PRIVATE);
		assertThat(visibilityOf(PrivateCtor.class)).isEqualTo(Visibility.PRIVATE);
	}
	
	// Member access and hidden nestmates
	
	static class HiddenTarget{
		public static int value(){ return 42; }
	}
	
	public static class Target{
		private static int secret       = 7;
		private        int privateValue = 11;
		private int revealInstance(){ return privateValue; }
		static           int packageField   = 8;
		protected static int protectedField = 9;
		public static    int publicField    = 42;
		protected        int instanceField  = 7;
		protected int instanceValue()        { return instanceField; }
		
		public Target()                      { }
		private Target(int ignored)          { }
		
		private static int reveal()          { return secret; }
		static int packageValue()            { return packageField; }
		protected static int protectedValue(){ return protectedField; }
		public static int publicValue()      { return 42; }
	}
	
	@Test
	void privateMethodCallShouldBeRejectedAtBuild(){
		assertThatCode(() -> generate("reproaccess.PrivateMethodCaller", cd ->
			                                                                 cd.function("run").staticAcc().returns(int.class).body().call(Target.class, "reveal").returnOp()
		)).isInstanceOf(MalformedJorth.class)
		  .hasMessageContaining("reveal").hasMessageContaining("reproaccess.PrivateMethodCaller");
	}
	
	@Test
	void privateFieldAccessShouldBeRejectedAtBuild(){
		assertThatCode(() -> generate("reproaccess.PrivateFieldReader", cd ->
			                                                                cd.function("run").staticAcc().returns(int.class).body().get(Target.class, "secret").returnOp()
		)).isInstanceOf(MalformedJorth.class)
		  .hasMessageContaining("secret").hasMessageContaining("reproaccess.PrivateFieldReader");
	}
	
	@Test
	void privateFieldWriteShouldBeRejectedAtBuild(){
		assertThatCode(() -> generate("reproaccess.PrivateFieldWriter", cd ->
			                                                                cd.function("run").staticAcc().body().val(1).set(Target.class, "secret")
		)).isInstanceOf(MalformedJorth.class);
	}
	
	@Test
	void privateConstructorShouldBeRejectedAtBuild(){
		assertThatCode(() -> generate("reproaccess.PrivateConstructorCaller", cd ->
			                                                                      cd.function("run").staticAcc().returns(Target.class).body().newObj(Target.class, b -> b.val(1)).returnOp()
		)).isInstanceOf(MalformedJorth.class);
	}
	
	@Test
	void privateMembersWorkInHiddenNestmate() throws Exception{
		verifyHiddenNestmateAccess(MethodHandles.lookup());
	}
	
	@Test
	void privateMembersWorkWhenDefinitionHostIsAlreadyHidden() throws Exception{
		var cd = ClassDefinition.hiddenNestmate(ReproJorthTests.class);
		cd.name(ClassName.dotted("com.lapissea.jorth.HiddenBootstrapHost"));
		var hiddenLookup = MethodHandles.lookup().defineHiddenClass(cd.getClassFile(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
		assertThat(hiddenLookup.lookupClass().isHidden()).isTrue();
		verifyHiddenNestmateAccess(hiddenLookup);
	}
	
	private static void verifyHiddenNestmateAccess(MethodHandles.Lookup lookup) throws Exception{
		var cd = ClassDefinition.hiddenNestmate(lookup.lookupClass());
		cd.name(ClassName.dotted("com.lapissea.jorth.HiddenPrivateAccess"));
		cd.function("create").staticAcc().returns(Target.class).body()
		  .newObj(Target.class, b -> b.val(1)).returnOp();
		cd.function("instance").staticAcc().arg(Target.class, "receiver").returns(int.class).body()
		  .get("receiver").val(43).set(Target.class, "privateValue")
		  .get("receiver").get(Target.class, "privateValue").returnOp();
		cd.function("instanceMethod").staticAcc().arg(Target.class, "receiver").returns(int.class).body()
		  .get("receiver").call("revealInstance").returnOp();
		cd.function("staticField").staticAcc().returns(int.class).body()
		  .val(44).set(Target.class, "secret").get(Target.class, "secret").returnOp();
		cd.function("staticMethod").staticAcc().returns(int.class).body()
		  .call(Target.class, "reveal").returnOp();
		var cls = lookup.defineHiddenClass(cd.getClassFile(), true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
		assertThat(cls.getNestHost()).isEqualTo(ReproJorthTests.class);
		var instance = cls.getMethod("create").invoke(null);
		assertThat(cls.getMethod("instance", Target.class).invoke(null, instance)).isEqualTo(43);
		assertThat(cls.getMethod("instanceMethod", Target.class).invoke(null, instance)).isEqualTo(43);
		int previousSecret = Target.secret;
		try{
			assertThat(cls.getMethod("staticField").invoke(null)).isEqualTo(44);
			assertThat(cls.getMethod("staticMethod").invoke(null)).isEqualTo(44);
		}finally{
			Target.secret = previousSecret;
		}
	}
	
	@Test
	void ordinarySamePackageClassCannotAccessPrivateMembers(){
		assertThatCode(() -> generate("com.lapissea.jorth.OrdinaryPrivateAccess", cd ->
			                                                                          cd.function("run").staticAcc().returns(int.class).body().get(Target.class, "secret").returnOp()
		)).isInstanceOf(MalformedJorth.class).hasMessageContaining("secret");
	}
	
	@Test
	void hiddenNestmateCannotAccessUnrelatedPrivateMembers(){
		assertThatCode(() -> {
			var cd = ClassDefinition.hiddenNestmate(ReproJorthTests.class);
			cd.name(ClassName.dotted("com.lapissea.jorth.UnrelatedPrivateAccess"));
			cd.function("run").staticAcc().returns(int.class).body().get(UnrelatedAccessTarget.class, "secret").returnOp();
			cd.getClassFile();
		}).isInstanceOf(MalformedJorth.class).hasMessageContaining("secret");
	}
	
	@Test
	void packageAndProtectedMethodsShouldBeRejectedAcrossPackages(){
		for(var method : new String[]{"packageValue", "protectedValue"}){
			assertThatCode(() -> generate("reproaccess.InaccessibleMethodCaller", cd ->
				                                                                      cd.function("run").staticAcc().returns(int.class).body().call(Target.class, method).returnOp()
			)).as(method).isInstanceOf(MalformedJorth.class);
		}
	}
	
	@Test
	void packageAndProtectedFieldsShouldBeRejectedAcrossPackages(){
		for(var field : new String[]{"packageField", "protectedField"}){
			assertThatCode(() -> generate("reproaccess.InaccessibleFieldReader", cd ->
				                                                                     cd.function("run").staticAcc().returns(int.class).body().get(Target.class, field).returnOp()
			)).as(field).isInstanceOf(MalformedJorth.class);
			assertThatCode(() -> generate("reproaccess.InaccessibleFieldWriter", cd ->
				                                                                     cd.function("run").staticAcc().body().val(1).set(Target.class, field)
			)).as(field).isInstanceOf(MalformedJorth.class);
		}
	}
	
	@Test
	void publicMethodCallWorks() throws Exception{
		var cls = generateAndLoad("reproaccess.PublicMethodCaller", cd ->
			                                                            cd.function("run").staticAcc().returns(int.class).body().call(Target.class, "publicValue").returnOp()
		);
		assertThat(cls.getMethod("run").invoke(null)).isEqualTo(42);
	}
	
	@Test
	void publicConstructorWorks() throws Exception{
		var cls = generateAndLoad("reproaccess.PublicConstructorCaller", cd ->
			                                                                 cd.function("run").staticAcc().returns(Target.class).body().newObj(Target.class).returnOp()
		);
		assertThat(cls.getMethod("run").invoke(null)).isInstanceOf(Target.class);
	}
	
	@Test
	void publicFieldReadAndWriteWork() throws Exception{
		var cls = generateAndLoad("reproaccess.PublicFieldAccess", cd ->
			                                                           cd.function("run").staticAcc().returns(int.class).body()
			                                                             .val(42).set(Target.class, "publicField").get(Target.class, "publicField").returnOp()
		);
		assertThat(cls.getMethod("run").invoke(null)).isEqualTo(42);
	}
	
	@Test
	void ownPrivateMembersWork() throws Exception{
		var cls = generateAndLoad("reproaccess.OwnPrivateAccess", cd -> {
			cd.field(int.class, "secret").staticAcc().visibility(Visibility.PRIVATE);
			cd.function("reveal").staticAcc().visibility(Visibility.PRIVATE).returns(int.class).body()
			  .get(cd.name(), "secret").returnOp();
			cd.function("run").staticAcc().returns(int.class).body()
			  .val(42).set(cd.name(), "secret").call(cd.name(), "reveal").returnOp();
		});
		assertThat(cls.getMethod("run").invoke(null)).isEqualTo(42);
	}
	@Test
	void protectedInstanceMembersWorkOnSubclassReceiver() throws Exception{
		var cls = generateAndLoad("reproaccess.ProtectedSubclass", cd -> {
			cd.extendsType(Target.class);
			cd.instanceInit().body().callSuperAutoPass();
			cd.function("run").staticAcc().arg(cd.name(), "receiver").returns(int.class).body()
			  .get("receiver").val(42).set(Target.class, "instanceField")
			  .get("receiver").get(Target.class, "instanceField").pop()
			  .get("receiver").call("instanceValue").returnOp();
		});
		assertThat(cls.getMethod("run", cls).invoke(null, cls.getConstructor().newInstance())).isEqualTo(42);
	}
	
	@Test
	void protectedInstanceMembersRejectBaseReceiver(){
		assertThatCode(() -> generate("reproaccess.ProtectedBaseReader", cd -> {
			cd.extendsType(Target.class);
			cd.function("run").staticAcc().arg(Target.class, "receiver").returns(int.class).body()
			  .get("receiver").get(Target.class, "instanceField").returnOp();
		})).isInstanceOf(MalformedJorth.class).hasMessageContaining("instanceField");
		assertThatCode(() -> generate("reproaccess.ProtectedBaseWriter", cd -> {
			cd.extendsType(Target.class);
			cd.function("run").staticAcc().arg(Target.class, "receiver").body()
			  .get("receiver").val(42).set(Target.class, "instanceField");
		})).isInstanceOf(MalformedJorth.class).hasMessageContaining("instanceField");
		assertThatCode(() -> generate("reproaccess.ProtectedBaseCaller", cd -> {
			cd.extendsType(Target.class);
			cd.function("run").staticAcc().arg(Target.class, "receiver").returns(int.class).body()
			  .get("receiver").call("instanceValue").returnOp();
		})).isInstanceOf(MalformedJorth.class).hasMessageContaining("instanceValue");
	}
	
	@Test
	void packageAndProtectedMembersWorkInSameRuntimePackage() throws Exception{
		var bytes = generate("com.lapissea.jorth.GeneratedSamePackageAccess", cd -> {
			cd.function("run").staticAcc().returns(int.class).body()
			  .val(8).set(Target.class, "packageField")
			  .val(9).set(Target.class, "protectedField")
			  .get(Target.class, "packageField").pop().get(Target.class, "protectedField").pop()
			  .call(Target.class, "packageValue").pop()
			  .call(Target.class, "protectedValue").returnOp();
		});
		var cls = MethodHandles.lookup().defineClass(bytes);
		assertThat(cls.getMethod("run").invoke(null)).isEqualTo(9);
	}
	
	@Test
	void publicMemberOfInaccessibleClassShouldBeRejected(){
		assertThatCode(() -> generate("reproaccess.HiddenClassCaller", cd ->
			                                                               cd.function("run").staticAcc().returns(int.class).body().call(HiddenTarget.class, "value").returnOp()
		)).isInstanceOf(MalformedJorth.class).hasMessageContaining("HiddenTarget");
	}
	
	// Class modifiers and inheritance
	
	@DataProvider
	Object[][] classAccess(){
		return new Object[][]{{AccessSet.DEFAULT}, {AccessSet.FINAL}, {AccessSet.ABSTRACT}};
	}
	
	@Test(dataProvider = "classAccess")
	void emittedModifiersMatchClassAccess(AccessSet access) throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reprofinal.Base"));
		cd.access(access);
		var cls = loadSingleClass("reprofinal.Base", cd.getClassFile());
		assertThat(Modifier.isFinal(cls.getModifiers())).isEqualTo(access.isFinal());
		assertThat(Modifier.isAbstract(cls.getModifiers())).isEqualTo(access.isAbstract());
		assertThat(cls.isSealed()).isFalse();
	}
	
	@Test
	void finalAccEmitsFinalClass() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reprofinal.Final"));
		cd.finalAcc();
		assertThat(Modifier.isFinal(loadSingleClass("reprofinal.Final", cd.getClassFile()).getModifiers())).isTrue();
	}
	
	@Test
	void generatedBaseClassCanBeExtended() throws Exception{
		var loader = newLoader(Set.of("reprofinal.HierBase", "reprofinal.HierChild"), (name, cd) -> {
			if(name.equals("reprofinal.HierChild")){
				cd.extendsType(ClassName.dotted("reprofinal.HierBase"));
			}
		});
		var base  = Class.forName("reprofinal.HierBase", true, loader);
		var child = Class.forName("reprofinal.HierChild", true, loader);
		assertThat(child.getSuperclass()).isSameAs(base);
		assertThat(child.getConstructor().newInstance()).isInstanceOf(base);
	}
	
	private static ClassLoader newLoader(Set<String> names, UnsafeBiConsumer<String, ClassDefinition, MalformedJorth> generator){
		return new ClassLoader(ReproJorthTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(names.contains(name)){
					byte[] bytes;
					try{
						var cd = new ClassDefinition(this);
						cd.name(ClassName.dotted(name));
						generator.accept(name, cd);
						bytes = cd.getClassFile();
					}catch(Throwable e){
						throw new RuntimeException(e);
					}
					return defineClass(name, bytes, 0, bytes.length);
				}
				return super.findClass(name);
			}
		};
	}
	
	// Field initialization
	
	public static class Base{
		public final int value;
		public Base(int value){ this.value = value; }
		public int value()    { return value; }
	}
	
	@Test
	void emptyConstructorInitializesFields() throws Exception{
		var cls = generateAndLoad(autoName(), cd -> {
			cd.instanceInit().body();
			cd.field(int.class, "x").finalAcc(b -> b.val(42));
			cd.getClassFile(); // Repeated emission must retain the fallback.
		});
		assertThat(cls.getField("x").get(cls.getConstructor().newInstance())).isEqualTo(42);
	}
	
	@Test
	void missingSuperInitializesFieldsBeforeBody() throws Exception{
		var cls = generateAndLoad(autoName(), cd -> {
			cd.field(int.class, "x").finalAcc(b -> b.val(42));
			cd.field(int.class, "observed");
			cd.field(int.class, "argument").finalAcc(b -> b.get("unused"));
			cd.instanceInit().arg(int.class, "unused").body()
			  .getThis("x").setThis("observed").returnOp();
		});
		var instance = cls.getConstructor(int.class).newInstance(7);
		assertThat(cls.getField("observed").get(instance)).isEqualTo(42);
		assertThat(cls.getField("argument").get(instance)).isEqualTo(7);
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void missingSuperRequiresAccessibleNoArgConstructor() throws Exception{
		generateAndLoad(autoName(), cd -> {
			cd.extendsType(Base.class);
			cd.instanceInit().body();
		});
	}
	
	@Test
	void instanceInitializerRunsForEachInstance() throws Exception{
		var cls = generateAndLoad(autoName(), cd -> {
			cd.field(int.class, "x").finalAcc(b -> b.val(42));
			cd.field(Object.class, "object").finalAcc(b -> b.newObj(Object.class));
		});
		var first  = cls.getConstructor().newInstance();
		var second = cls.getConstructor().newInstance();
		assertThat(cls.getField("x").get(first)).isEqualTo(42);
		assertThat(cls.getField("x").get(second)).isEqualTo(42);
		assertThat(cls.getField("object").get(first)).isNotSameAs(cls.getField("object").get(second));
	}
	
	@Test
	void lateInitializersRunAfterSuperInEveryConstructor() throws Exception{
		var cls = generateAndLoad(autoName(), cd -> {
			cd.extendsType(Base.class);
			cd.instanceInit().body().callSuper(b -> b.val(12));
			cd.instanceInit().arg(int.class, "value").body().callSuperAutoPass();
			cd.field(int.class, "x").finalAcc(b -> b.get("this").call("value"));
			cd.field(long.class, "wide").finalAcc(b -> b.val(42L));
		});
		var first  = cls.getConstructor().newInstance();
		var second = cls.getConstructor(int.class).newInstance(27);
		assertThat(cls.getField("x").get(first)).isEqualTo(12);
		assertThat(cls.getField("x").get(second)).isEqualTo(27);
		assertThat(cls.getField("wide").get(second)).isEqualTo(42L);
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void instanceInitializerRequiresOneValue() throws Exception{
		generateAndLoad(autoName(), cd ->
			                            cd.field(int.class, "x").finalAcc(b -> b.val(1).val(42)));
	}
	
	@Test
	void staticFinalInitializerWorks() throws Exception{
		var cls = generateAndLoad(autoName(), cd ->
			                                      cd.field(int.class, "x").staticFinal(b -> b.val(42)));
		assertThat(cls.getField("x").get(null)).isEqualTo(42);
	}
	
	// Sealed hierarchies
	
	private void defineHierarchy(String dottedName, ClassDefinition cd) throws MalformedJorth{
		cd.name(ClassName.dotted(dottedName));
		switch(dottedName){
			case "SealedClass" -> {
				cd.permits(ClassName.dotted("child1")).permits(ClassName.dotted("child2"));
			}
			case "child1", "child2" -> cd.finalAcc().extendsType(ClassName.dotted("SealedClass"));
			default -> throw new IllegalStateException("Unexpected class " + dottedName);
		}
	}
	
	private Map<String, Class<?>> generateAndLoad(
		List<String> dottedNames,
		UnsafeBiConsumer<String, ClassDefinition, MalformedJorth> generator
	) throws Exception{
		var loader = newLoader(Set.copyOf(dottedNames), generator);
		
		Map<String, Class<?>> loaded = new LinkedHashMap<>();
		for(String n : dottedNames){
			loaded.put(n, Class.forName(n, true, loader));
		}
		return loaded;
	}
	
	@Test
	public void generatedClassWithPermitsIsSealed() throws Exception{
		var classes = generateAndLoad(List.of("SealedClass", "child1", "child2"), this::defineHierarchy);
		var parent  = classes.get("SealedClass");
		assertThat(parent.isSealed()).isTrue();
		assertThat(parent.getPermittedSubclasses())
			.containsExactlyInAnyOrder(classes.get("child1"), classes.get("child2"));
		for(String name : List.of("child1", "child2")){
			var child = classes.get(name);
			assertThat(child.isSealed()).isFalse();
			assertThat(child.getConstructor().newInstance()).isInstanceOf(parent);
		}
	}
	
	// Annotation values
	
	public enum Color{
		RED, GREEN{
			@Override
			public String toString(){ return "green"; }
		}
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	public @interface MyAnn{
		Color c() default Color.RED;
		Color[] cs() default {Color.RED};
		int[] ints() default {};
	}
	
	@Test
	void enumArrayAnnotationMemberWorks() throws Exception{
		var cls = generateAndLoad("reproannenumarray.EnumArrayAnn", cd ->
			                                                            cd.annotation(MyAnn.class, a -> a.arg("cs", new Color[]{Color.RED, Color.GREEN, Color.RED}))
		);
		assertThat(cls.getAnnotation(MyAnn.class).cs()).containsExactly(Color.RED, Color.GREEN, Color.RED);
	}
	
	@Test
	void emptyEnumArrayOverridesDefault() throws Exception{
		var cls = generateAndLoad("reproannenumarray.EmptyEnumArrayAnn", cd ->
			                                                                 cd.annotation(MyAnn.class, a -> a.arg("cs", new Color[0]))
		);
		assertThat(cls.getAnnotation(MyAnn.class).cs()).isEmpty();
	}
	
	@Test
	void topLevelEnumAnnotationMemberWorks() throws Exception{
		var cls = generateAndLoad("reproannenumarray.TopLevelEnumAnn", cd -> {
			cd.annotation(MyAnn.class, a -> a.arg("c", Color.GREEN));
		});
		MyAnn ann = cls.getAnnotation(MyAnn.class);
		assertThat(ann)
			.as("the generated class must carry the runtime-visible @MyAnn annotation")
			.isNotNull();
		assertThat(ann.c())
			.as("the c() top-level enum member must be GREEN (constant with a class body)")
			.isEqualTo(Color.GREEN);
	}
	
	@Test
	void nonEnumArrayAnnotationMemberWorks() throws Exception{
		var cls = generateAndLoad("reproannenumarray.NonEnumArrayAnn", cd -> {
			cd.annotation(MyAnn.class, a -> a.arg("ints", new int[]{1, 2, 3}));
		});
		MyAnn ann = cls.getAnnotation(MyAnn.class);
		assertThat(ann)
			.as("the generated class must carry the runtime-visible @MyAnn annotation")
			.isNotNull();
		assertThat(ann.ints())
			.as("the ints() int-array member must be [1, 2, 3]")
			.containsExactly(1, 2, 3);
	}
	
	// Annotation types
	
	@Test
	void annotationClassMustHaveAccAnnotationFlag() throws Exception{
		var gen = generateWithBytes("reproannotationtype.Marker", cd -> {
			cd.type(ClassType.ANNOTATION);
			cd.function("value").returns(String.class);
		});
		int flags = Opcodes.ACC_ANNOTATION|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;
		assertThat(new ClassReader(gen.bytes()).getAccess()&flags).isEqualTo(flags);
		assertThat(new ClassReader(gen.bytes()).getInterfaces()).containsExactly("java/lang/annotation/Annotation");
		assertThat(gen.cls().isAnnotation()).isTrue();
		assertThat(gen.cls().isInterface()).isTrue();
		assertThat(Annotation.class.isAssignableFrom(gen.cls())).isTrue();
	}
	
	@Test
	void generatedAnnotationCanBeReadBack() throws Exception{
		var gen = generateWithBytes("reproannotationtype.RuntimeAnnotation", cd -> {
			cd.type(ClassType.ANNOTATION);
			cd.annotation(Retention.class, a -> a.arg("value", RetentionPolicy.RUNTIME));
			cd.function("value").returns(String.class);
		});
		var annotationType = gen.cls().asSubclass(Annotation.class);
		var target = generateWithBytes("reproannotationtype.AnnotatedClass", annotationType.getClassLoader(), cd ->
			                                                                                                      cd.annotation(annotationType, a -> a.arg("value", "hello"))
		);
		var annotation = target.cls().getAnnotation(annotationType);
		assertThat(annotation).isNotNull();
		assertThat(annotation.annotationType()).isEqualTo(annotationType);
		assertThat(annotationType.getMethod("value").invoke(annotation)).isEqualTo("hello");
	}
	
	@Test
	void interfaceClassIsNotAnnotation() throws Exception{
		var gen = generateWithBytes("reproannotationtype.PlainInterface", cd -> {
			cd.type(ClassType.INTERFACE);
			cd.function("hello").returns(String.class);
		});
		assertThat(gen.cls().isInterface()).isTrue();
		assertThat(gen.cls().isAnnotation()).isFalse();
		assertThat(new ClassReader(gen.bytes()).getAccess()&Opcodes.ACC_ANNOTATION).isZero();
		assertThat(Annotation.class.isAssignableFrom(gen.cls())).isFalse();
	}
	
	@Test
	void plainClassIsNotAnnotation() throws Exception{
		var gen = generateWithBytes("reproannotationtype.PlainClass", cd -> { });
		assertThat(gen.cls().isInterface()).isFalse();
		assertThat(gen.cls().isAnnotation()).isFalse();
		assertThat(new ClassReader(gen.bytes()).getAccess()&Opcodes.ACC_ANNOTATION).isZero();
	}
	
	// Signature normalization
	
	@Test
	void nonGenericParametersAreUnchanged(){
		var sig = new FunctionInfo.Signature("control", List.of(GenericType.INT, GenericType.STRING));
		assertThat(sig.name()).isEqualTo("control");
		assertThat(sig.args()).containsExactly(GenericType.INT, GenericType.STRING);
	}
	
	@Test
	void parameterizedSignatureMatchesRawLookupKey(){
		var raw           = new FunctionInfo.Signature("takeList", List.of(GenericType.of(List.class)));
		var parameterized = new FunctionInfo.Signature("takeList", List.of(GenericType.of(List.class).withArgs(String.class)));
		assertThat(parameterized).isEqualTo(raw);
		assertThat(Map.of(raw, "found").get(parameterized)).isEqualTo("found");
	}
	
	@Test
	void stripsAllGenericParametersAndPreservesArrayDimensions(){
		var list  = GenericType.of(List.class).withArgs(String.class);
		var array = list.arrayType();
		var input = new ArrayList<JType>(List.of(GenericType.INT, list, GenericType.STRING, array));
		var sig   = new FunctionInfo.Signature("mixed", input);
		assertThat(sig.args()).containsExactly(GenericType.INT, GenericType.of(List.class), GenericType.STRING,
		                                       GenericType.of(List.class).arrayType());
		assertThat(input).containsExactly(GenericType.INT, list, GenericType.STRING, array);
		assertThat(list.hasArgs()).isTrue();
		assertThat(array.hasArgs()).isTrue();
		input.clear();
		assertThat(sig.args()).hasSize(4);
	}
	
	@Test(expectedExceptions = UnsupportedOperationException.class)
	void normalizedArgumentsRemainImmutable(){
		var sig = new FunctionInfo.Signature("takeList", List.of(GenericType.of(List.class).withArgs(String.class)));
		sig.args().clear();
	}
	
	// Type variable bounds and JVM encodings
	
	@DataProvider
	Object[][] primitives(){
		return new Object[][]{
			{boolean.class}, {byte.class}, {short.class}, {char.class}, {int.class},
			{long.class}, {float.class}, {double.class}, {void.class}
		};
	}
	
	@Test(dataProvider = "primitives", expectedExceptions = IllegalArgumentException.class,
	      expectedExceptionsMessageRegExp = "Type variable bound must be a reference type: .*")
	void primitiveBoundIsRejected(Class<?> primitive){
		GenericType.of(primitive).withTypeArgName(ClassName.dotted("T"));
	}
	
	@Test(expectedExceptions = IllegalArgumentException.class,
	      expectedExceptionsMessageRegExp = "Type variable bound must be a reference type: .*")
	void directConstructionRejectsPrimitiveBound(){
		new GenericType(GenericType.INT.raw(), Optional.of(ClassName.dotted("T")), 0, List.of());
	}
	
	@Test
	void referenceBoundPreservesVariableSignatureAndErasedDescriptor(){
		var type = GenericType.of(Number.class).withTypeArgName(ClassName.dotted("T"));
		assertEncoding(type, true, "TT;");
		assertEncoding(type, false, "Ljava/lang/Number;");
		assertEncoding(type.arrayType(), true, "[TT;");
		assertEncoding(type.arrayType(), false, "[Ljava/lang/Number;");
	}
	
	@Test
	void plainPrimitivePreservesDescriptor(){
		assertEncoding(GenericType.BYTE, true, "B");
		assertEncoding(GenericType.BYTE, false, "B");
		assertEncoding(GenericType.BYTE.arrayType(), true, "[B");
	}
	
	private static void assertEncoding(GenericType type, boolean generics, String expected){
		var text = new StringBuilder();
		type.jvmString(text, generics);
		assertThat(text.toString()).isEqualTo(expected);
		assertThat(type.jvmStringLen(generics)).isEqualTo(expected.length());
		assertThat(type.jvmString(generics).toString()).isEqualTo(expected);
	}
	
	// Wildcards
	
	public List<?> unbounded;
	
	@Test
	void unboundedWildcardResolvesToObject(){
		assertThat(JType.WILDCARD.asGeneric()).isEqualTo(GenericType.OBJECT);
		assertThat(JType.WILDCARD.withoutArgs()).isEqualTo(GenericType.OBJECT);
		assertThat(JType.WILDCARD.jvmSignatureStr()).isEqualTo("*");
		assertThat(JType.WILDCARD.jvmSignatureLen()).isEqualTo(1);
		assertThat(JType.WILDCARD.jvmDescriptorStr()).isEqualTo("Ljava/lang/Object;");
		assertThat(JType.WILDCARD.jvmDescriptorLen()).isEqualTo("Ljava/lang/Object;".length());
	}
	
	@Test
	void reflectedUnboundedWildcardMatchesExplicitWildcard() throws Exception{
		var fieldType = (ParameterizedType)getClass().getField("unbounded").getGenericType();
		var reflected = JType.of(fieldType.getActualTypeArguments()[0]);
		assertThat(reflected.asGeneric()).isEqualTo(JType.WILDCARD.asGeneric());
		assertThat(reflected.jvmSignatureStr()).isEqualTo(JType.WILDCARD.jvmSignatureStr());
		assertThat(reflected.jvmDescriptorStr()).isEqualTo(JType.WILDCARD.jvmDescriptorStr());
	}
	
	@Test
	void boundedWildcardsRetainExistingResolution(){
		assertThat(JType.upper(Number.class).asGeneric()).isEqualTo(GenericType.of(Number.class));
		assertThat(JType.lower(String.class).asGeneric()).isEqualTo(GenericType.STRING);
		assertThat(JType.upper(Number.class).jvmSignatureStr()).isEqualTo("+Ljava/lang/Number;");
		assertThat(JType.lower(String.class).jvmSignatureStr()).isEqualTo("-Ljava/lang/String;");
	}
	
	@Test
	void wildcardTypeArgumentKeepsWildcardSignature(){
		var type = GenericType.of(List.class).withArgs(List.of(JType.WILDCARD));
		assertThat(type.jvmSignatureStr()).isEqualTo("Ljava/util/List<*>;");
		assertThat(type.jvmDescriptorStr()).isEqualTo("Ljava/util/List;");
	}
	
	// Array type caching
	
	@DataProvider
	Object[][] lookupOrders(){
		return new Object[][]{
			{Integer.class, new int[]{0, 1, 2}}, {Integer.class, new int[]{2, 1, 0}},
			{int.class, new int[]{0, 1, 2}}, {int.class, new int[]{2, 1, 0}}
		};
	}
	
	@Test(dataProvider = "lookupOrders")
	void cacheDistinguishesDimensions(Class<?> component, int[] order) throws MalformedJorth{
		var source   = TypeSource.of(null, getClass().getClassLoader());
		var base     = GenericType.of(component);
		var resolved = new ClassInfo[3];
		for(int dims : order){
			resolved[dims] = source.byType(base.withDims(dims));
			assertThat(resolved[dims]).isInstanceOf(dims == 0? ClassInfo.OfClass.class : ClassInfo.OfArray.class);
		}
		assertThat(resolved[0]).isNotSameAs(resolved[1]);
		assertThat(resolved[1]).isNotSameAs(resolved[2]);
		for(int dims = 0; dims<3; dims++){
			assertThat(source.byType(base.withDims(dims))).isSameAs(resolved[dims]);
		}
	}
	
	@Test
	void arrayLookupDoesNotHideComponentFields() throws Exception{
		var cls = generateAndLoad("test.ArrayAndComponent", cd ->
			                                                    cd.function("maxValue").staticAcc().returns(int.class).body()
			                                                      .val(3).newObj(Integer[].class).call("hashCode").pop()
			                                                      .get(Integer.class, "MAX_VALUE"));
		assertThat(cls.getMethod("maxValue").invoke(null)).isEqualTo(Integer.MAX_VALUE);
	}
}

// Must remain outside the test class so it is not a nestmate.
class UnrelatedAccessTarget{
	private static int secret = 17;
}
