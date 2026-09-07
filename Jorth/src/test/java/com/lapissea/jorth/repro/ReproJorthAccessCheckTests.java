package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression coverage for rejecting inaccessible members before emitting bytecode.
 */
public class ReproJorthAccessCheckTests{
	
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
	
	private static byte[] generate(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws MalformedJorth{
		var cd = new ClassDefinition(ReproJorthAccessCheckTests.class.getClassLoader());
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		return cd.getClassFile();
	}
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		byte[] bytes = generate(className, generator);
		var loader = new ClassLoader(ReproJorthAccessCheckTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(name.equals(className)) return defineClass(name, bytes, 0, bytes.length);
				return super.findClass(name);
			}
		};
		return Class.forName(className, true, loader);
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
		var cd = ClassDefinition.hiddenNestmate(ReproJorthAccessCheckTests.class);
		cd.name(ClassName.dotted("com.lapissea.jorth.repro.HiddenBootstrapHost"));
		var hiddenLookup = MethodHandles.lookup().defineHiddenClass(cd.getClassFile(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
		assertThat(hiddenLookup.lookupClass().isHidden()).isTrue();
		verifyHiddenNestmateAccess(hiddenLookup);
	}
	
	private static void verifyHiddenNestmateAccess(MethodHandles.Lookup lookup) throws Exception{
		var cd = ClassDefinition.hiddenNestmate(lookup.lookupClass());
		cd.name(ClassName.dotted("com.lapissea.jorth.repro.HiddenPrivateAccess"));
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
		assertThat(cls.getNestHost()).isEqualTo(ReproJorthAccessCheckTests.class);
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
		assertThatCode(() -> generate("com.lapissea.jorth.repro.OrdinaryPrivateAccess", cd ->
			                                                                                cd.function("run").staticAcc().returns(int.class).body().get(Target.class, "secret").returnOp()
		)).isInstanceOf(MalformedJorth.class).hasMessageContaining("secret");
	}
	
	@Test
	void hiddenNestmateCannotAccessUnrelatedPrivateMembers(){
		assertThatCode(() -> {
			var cd = ClassDefinition.hiddenNestmate(ReproJorthAccessCheckTests.class);
			cd.name(ClassName.dotted("com.lapissea.jorth.repro.UnrelatedPrivateAccess"));
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
		var bytes = generate("com.lapissea.jorth.repro.GeneratedSamePackageAccess", cd -> {
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
	
}

class UnrelatedAccessTarget{
	private static int secret = 17;
}
