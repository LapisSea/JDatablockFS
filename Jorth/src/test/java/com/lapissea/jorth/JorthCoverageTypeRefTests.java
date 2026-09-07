package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static com.lapissea.jorth.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Missing functional coverage (MT items) for type/reference handling in the Jorth DSL:
 * typeDef forward references and ClassDefinition lookup APIs, self-recursive classes,
 * remaining call/new overloads, ClassName/GenericType type overloads, callVirtual
 * required-field guards and same-class mutual recursion.
 */
public class JorthCoverageTypeRefTests{
	
	/** Static target for the call(Class,String) zero-arg overload. */
	public static int answer(){
		return 42;
	}
	/** Static target for the call(Class,String,CodeArg) overload. */
	public static int sum(int a, int b){
		return a + b;
	}
	
	// ------------------------------------------------------------------ helpers
	
	/** Builds the class file without loading it, so the emitted bytes can be inspected. */
	private static byte[] generateBytes(UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws MalformedJorth{
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(autoName()));
		generator.accept(cd);
		return cd.getClassFile();
	}
	
	// ------------------------------------------------------------------ MT-22
	
	// MT-22 — typeDef forward reference: a field of a type that is not generated (TemplateClassLoader pattern) builds and loads
	@Test
	void mt22_typeDefForwardRef() throws Exception{
		var cdRef = new ClassDefinition[1];
		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
			cdRef[0] = cd;
			cd.typeDef("Foo", ClassName.dotted("test.Foo"));
			cd.field(GenericType.of(ClassName.dotted("test.Foo")), "foo");
		};
		var bytes = generateBytes(builder);
		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
		
		assertThat(cdRef[0].getTypeDef("Foo")).isEqualTo(ClassName.dotted("test.Foo"));
		assertThat(((GenericType)cdRef[0].getField("foo").type()).raw()).isEqualTo(ClassName.dotted("test.Foo"));
		assertThat(new String(bytes, StandardCharsets.UTF_8))
		  .as("the field descriptor must reference the forward type by name")
		  .contains("Ltest/Foo;");
		assertThat(cls).as("class must load despite the unresolved field type").isNotNull();
		
		assertThatThrownBy(() -> cdRef[0].getTypeDef("missing"))
		  .isInstanceOf(IllegalStateException.class)
		  .hasMessageContaining("No type definition found with name missing");
		
		assertThatThrownBy(() -> cdRef[0].typeDef("Foo", ClassName.dotted("test.Bar")))
		  .isInstanceOf(IllegalArgumentException.class)
		  .hasMessageContaining("Type definition Foo already defined");
	}
	
	// MT-22 — lookup APIs: superType(), getArg(ClassName), getFunctionOverride(Signature)
	@Test
	void mt22_lookupApis() throws Exception{
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(autoName()));
		
		assertThat(cd.superType().name()).as("default extension is Object").isEqualTo(ClassName.dotted("java.lang.Object"));
		
		cd.genericArg(GenericType.of(String.class), ClassName.dotted("T"));
		assertThat(cd.getArg(ClassName.dotted("T")).raw()).isEqualTo(ClassName.dotted("java.lang.String"));
		assertThatThrownBy(() -> cd.getArg(ClassName.dotted("missing")))
		  .isInstanceOf(IllegalArgumentException.class)
		  .hasMessageContaining("No argument found with name missing");
		
		cd.implement(Supplier.class);
		var get = cd.getFunctionOverride(new FunctionInfo.Signature("get", List.of()));
		assertThat(get.name()).isEqualTo("get");
		assertThat(get.ownerInfo().name()).isEqualTo(ClassName.dotted("java.util.function.Supplier"));
		assertThatThrownBy(() -> cd.getFunctionOverride(new FunctionInfo.Signature("nope", List.of())))
		  .isInstanceOf(MalformedJorth.class)
		  .hasMessageContaining("does not exist");
	}
	
	// MT-22 — getClassInfo() live view: fields and functions added after obtaining the view are visible
	@Test
	void mt22_classInfoLiveView() throws Exception{
		var cd   = new ClassDefinition(null);
		cd.name(ClassName.dotted(autoName()));
		var info = cd.getClassInfo();
		
		assertThat(info.name()).isEqualTo(cd.name());
		
		var f = cd.field(int.class, "live");
		assertThat(info.getField("live")).isSameAs(f);
		
		var fn = cd.function("liveFn").returns(int.class);
		fn.body().val(1);
		assertThat(info.getFunction(new FunctionInfo.Signature("liveFn", List.of()))).isSameAs(fn);
		
		assertThatThrownBy(() -> info.getField("missing"))
		  .isInstanceOf(MalformedJorth.class)
		  .hasMessageContaining("does not exist");
	}
	
	// ------------------------------------------------------------------ MT-23
	
	// MT-23 — self-recursive generated class: a field of the class's own type resolves through the generatedClassInfo self-view
	@Test
	void mt23_selfRecursiveClass() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var self = GenericType.of(cd.name());
			cd.field(self, "next");
			cd.function("link").arg(self, "n").body().get("n").setThis("next");
			cd.function("getNext").returns(self).body().getThis("next");
			cd.function("nextId").returns(String.class).body().getThis("next").call("toString");
		});
		
		var inst  = cls.getDeclaredConstructor().newInstance();
		var other = cls.getDeclaredConstructor().newInstance();
		
		assertThat(cls.getMethod("link", cls).invoke(inst, other)).isNull();
		assertThat(cls.getMethod("getNext").invoke(inst)).isSameAs(other);
		
		assertThat(cls.getMethod("link", cls).invoke(inst, inst)).isNull();
		assertThat(cls.getMethod("getNext").invoke(inst)).isSameAs(inst);
		assertThat(cls.getMethod("nextId").invoke(inst)).isEqualTo(inst.toString());
	}
	
	// ------------------------------------------------------------------ MT-25
	
	// MT-25 — call(Class,String)/call(ClassName,String): static zero-arg calls, foreign class and same class
	@Test
	void mt25_callStaticZeroArg() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(int.class, "counter").staticAcc();
			cd.function("count").staticAcc().returns(int.class)
			  .body().get(cd.name(), "counter");
			cd.function("viaClass").staticAcc().returns(int.class)
			  .body().call(JorthCoverageTypeRefTests.class, "answer");
			cd.function("viaName").staticAcc().returns(int.class)
			  .body().val(7).set(cd.name(), "counter").call(cd.name(), "count");
		});
		
		assertThat(cls.getMethod("viaClass").invoke(null)).as("call via Class").isEqualTo(42);
		assertThat(cls.getMethod("viaName").invoke(null)).as("call via ClassName of the same class").isEqualTo(7);
		assertThat(cls.getField("counter").get(null)).isEqualTo(7);
	}
	
	// MT-25 — call(Class,String,CodeArg): static call with arguments collected from the stack
	@Test
	void mt25_callStaticWithArgs() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().returns(int.class)
			  .body().call(JorthCoverageTypeRefTests.class, "sum", c -> c.val(20).val(22));
		});
		
		assertThat(cls.getMethod("test").invoke(null)).isEqualTo(42);
	}
	
	// MT-25 — newObj(ClassName)/newObj(ClassName,CodeArg): non-array construction by ClassName
	@Test
	void mt25_newObjClassName() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("empty").staticAcc().returns(String.class)
			  .body().newObj(ClassName.dotted("java.lang.StringBuilder")).call("toString");
			cd.function("withArg").staticAcc().returns(String.class)
			  .body().newObj(ClassName.dotted("java.lang.StringBuilder"), c -> c.val("hi")).call("toString");
		});
		
		assertThat(cls.getMethod("empty").invoke(null)).isEqualTo("");
		assertThat(cls.getMethod("withArg").invoke(null)).isEqualTo("hi");
	}
	
	// MT-25 — call(FunctionDefinition,CodeArg) with a wrong argument count is rejected at build time
	@Test
	void mt25_callFunctionDefWrongArgCount() throws Exception{
		var fnRef = new FunctionDefinition[1];
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			fnRef[0] = cd.function("two").staticAcc()
			             .arg(int.class, "a").arg(int.class, "b").returns(int.class);
			fnRef[0].body().get("a");
			cd.function("test").staticAcc().returns(int.class)
			  .body().call(fnRef[0], c -> c.val(5));
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("Function argument takes 2 but got 1");
	}
	
	// ------------------------------------------------------------------ MT-26
	
	// MT-26 — genericArg(TypeVariable)/genericArg(GenericType,ClassName)/implement(ClassName)/extendsType(GenericType)
	@Test
	void mt26_classLevelTypeOverloads() throws Exception{
		var e   = List.class.getTypeParameters()[0];
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.genericArg(e);
			cd.genericArg(GenericType.of(String.class), ClassName.dotted("S"));
			cd.extendsType(GenericType.of(ArrayList.class).withArgs(String.class));
			cd.implement(ClassName.dotted("java.io.Serializable"));
		});
		
		var params = cls.getTypeParameters();
		assertThat(params).hasSize(2);
		assertThat(params[0].getName()).isEqualTo("E");
		assertThat(params[0].getBounds()).containsExactly(Object.class);
		assertThat(params[1].getName()).isEqualTo("S");
		assertThat(params[1].getBounds()).containsExactly(String.class);
		
		var sup = cls.getGenericSuperclass();
		assertThat(sup).isInstanceOf(ParameterizedType.class);
		var pt = (ParameterizedType)sup;
		assertThat(pt.getRawType()).isEqualTo(ArrayList.class);
		assertThat(pt.getActualTypeArguments()).containsExactly(String.class);
		
		assertThat(cls.getInterfaces()).containsExactly(Serializable.class);
		assertThat(cls.getGenericInterfaces()).containsExactly(Serializable.class);
	}
	
	// MT-26 — var(GenericType,String)/nullVal(ClassName)/nullVal(GenericType)/val(ClassName)
	@Test
	void mt26_codeBlockTypeOverloads() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("local").staticAcc().returns(String.class)
			  .body().var(GenericType.of(String.class), "s").set("s", "hi").get("s");
			cd.function("nullName").staticAcc().returns(Object.class)
			  .body().nullVal(ClassName.dotted("java.lang.String"));
			cd.function("nullGeneric").staticAcc().returns(Object.class)
			  .body().nullVal(GenericType.of(String.class));
			cd.function("classVal").staticAcc().returns(Class.class)
			  .body().val(ClassName.dotted("java.lang.String"));
		});
		
		assertThat(cls.getMethod("local").invoke(null)).isEqualTo("hi");
		assertThat(cls.getMethod("nullName").invoke(null)).isNull();
		assertThat(cls.getMethod("nullGeneric").invoke(null)).isNull();
		assertThat(cls.getMethod("classVal").invoke(null)).isEqualTo(String.class);
	}
	
	// ------------------------------------------------------------------ MT-27
	
	// MT-27 — callVirtual with a bootstrap that sets no owner is rejected
	@Test
	void mt27_callVirtualMissingOwner() throws Exception{
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().returns(String.class)
			  .body().callVirtual(b -> { },
			                     fn -> fn.name("make").arg(int.class).returns(String.class),
			                     c -> c.val(1));
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("Bootstrap function must be specified");
	}
	
	// MT-27 — callVirtual with a call site that sets no name is rejected
	@Test
	void mt27_callVirtualMissingName() throws Exception{
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().returns(String.class)
			  .body().callVirtual(b -> b.caller(JorthTests.TestBootstrap.class, "bootstrap"),
			                     fn -> fn.arg(int.class).returns(String.class),
			                     c -> c.val(1));
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("Calling function name must be specified");
	}
	
	// MT-27 — callVirtual positive: the bootstrap StaticArg carries a Class constant (extends the virtualCall pattern)
	@Test
	void mt27_callVirtualStaticArgClassConstant() throws Exception{
		var name = autoName();
		var cls  = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name)).implement(GenericType.of(IntFunction.class).withArgs(String.class));
			cd.function("apply").arg(int.class, "num").returns(Object.class).annotation(Override.class)
			  .body()
			  .callVirtual(b -> b.caller(JorthTests.TestBootstrap.class, "bootstrap").arg(Class.class, String.class),
			               fn -> fn.name("makeString").arg(int.class).returns(String.class),
			               c -> c.get("num"));
		});
		
		Object instO = cls.getConstructor().newInstance();
		//noinspection unchecked
		IntFunction<String> inst = (IntFunction<String>)instO;
		assertThat(inst.apply(7)).isEqualTo("java.lang.String 7");
	}
	
	// ------------------------------------------------------------------ MT-28
	
	// MT-28 — same-class mutual recursion a<->b via call(FunctionDefinition), bounded by the counter argument
	@Test
	void mt28_sameClassMutualRecursion() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var a = cd.function("a").staticAcc().arg(int.class, "n").returns(int.class);
			var b = cd.function("b").staticAcc().arg(int.class, "n").returns(int.class);
			a.body()
			  .get("n").val(0).ifEquality(c -> c.val(0).returnOp())
			  .call(b, c -> c.get("n").add(-1));
			b.body()
			  .get("n").val(0).ifEquality(c -> c.val(100).returnOp())
			  .call(a, c -> c.get("n").add(-1));
		});
		
		assertThat(cls.getMethod("a", int.class).invoke(null, 0)).isEqualTo(0);
		assertThat(cls.getMethod("a", int.class).invoke(null, 1)).isEqualTo(100);
		assertThat(cls.getMethod("a", int.class).invoke(null, 2)).isEqualTo(0);
		assertThat(cls.getMethod("b", int.class).invoke(null, 0)).isEqualTo(100);
		assertThat(cls.getMethod("b", int.class).invoke(null, 2)).isEqualTo(100);
	}
}
