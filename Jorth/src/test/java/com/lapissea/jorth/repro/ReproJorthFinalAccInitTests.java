package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static com.lapissea.jorth.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthFinalAccInitTests{

	private static Class<?> generateAndLoadInstanceSimple(String name, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		var cd = new ClassDefinition(ReproJorthFinalAccInitTests.class.getClassLoader()).name(ClassName.dotted(name));
		generator.accept(cd);
		var bytes = cd.getClassFile();
		return new ClassLoader(ReproJorthFinalAccInitTests.class.getClassLoader()){
			Class<?> load(){ return defineClass(name, bytes, 0, bytes.length); }
		}.load();
	}

	public static class Base{
		public final int value;
		public Base(int value){ this.value = value; }
		public int value(){ return value; }
	}

	@Test
	void emptyConstructorInitializesFields() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.instanceInit().body();
			cd.field(int.class, "x").finalAcc(b -> b.val(42));
			cd.getClassFile(); // Repeated emission must retain the fallback.
		});
		assertThat(cls.getField("x").get(cls.getConstructor().newInstance())).isEqualTo(42);
	}

	@Test
	void missingSuperInitializesFieldsBeforeBody() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
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
		generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.extendsType(Base.class);
			cd.instanceInit().body();
		});
	}

	@Test
	void instanceInitializerRunsForEachInstance() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(int.class, "x").finalAcc(b -> b.val(42));
			cd.field(Object.class, "object").finalAcc(b -> b.newObj(Object.class));
		});
		var first = cls.getConstructor().newInstance();
		var second = cls.getConstructor().newInstance();
		assertThat(cls.getField("x").get(first)).isEqualTo(42);
		assertThat(cls.getField("x").get(second)).isEqualTo(42);
		assertThat(cls.getField("object").get(first)).isNotSameAs(cls.getField("object").get(second));
	}

	@Test
	void lateInitializersRunAfterSuperInEveryConstructor() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.extendsType(Base.class);
			cd.instanceInit().body().callSuper(b -> b.val(12));
			cd.instanceInit().arg(int.class, "value").body().callSuperAutoPass();
			cd.field(int.class, "x").finalAcc(b -> b.get("this").call("value"));
			cd.field(long.class, "wide").finalAcc(b -> b.val(42L));
		});
		var first = cls.getConstructor().newInstance();
		var second = cls.getConstructor(int.class).newInstance(27);
		assertThat(cls.getField("x").get(first)).isEqualTo(12);
		assertThat(cls.getField("x").get(second)).isEqualTo(27);
		assertThat(cls.getField("wide").get(second)).isEqualTo(42L);
	}

	@Test(expectedExceptions = MalformedJorth.class)
	void instanceInitializerRequiresOneValue() throws Exception{
		generateAndLoadInstanceSimple(autoName(), cd ->
			cd.field(int.class, "x").finalAcc(b -> b.val(1).val(42)));
	}

	@Test
	void staticFinalInitializerWorks() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd ->
			cd.field(int.class, "x").staticFinal(b -> b.val(42)));
		assertThat(cls.getField("x").get(null)).isEqualTo(42);
	}
}
