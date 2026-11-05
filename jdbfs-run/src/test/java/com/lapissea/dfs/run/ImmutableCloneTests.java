package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.BuilderProxyCompiler;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ImmutableCloneTests{
	
	@IOValue
	public static class Child extends IOInstance.Managed<Child>{
		private int value;
		public Child()         { }
		public Child(int value){ this.value = value; }
	}
	
	@IOValue
	@IOInstance.Order({"number", "name", "child"})
	public static class PublicCtor extends IOInstance.Managed<PublicCtor>{
		private final int    number;
		private final String name;
		private final Child  child;
		public PublicCtor(int number, String name, Child child){
			this.number = number;
			this.name = name;
			this.child = child;
		}
	}
	
	@IOValue
	@IOInstance.Order({"number", "child"})
	public static class PackageCtor extends IOInstance.Managed<PackageCtor>{
		private final int   number;
		private final Child child;
		PackageCtor(int number, Child child){
			this.number = number;
			this.child = child;
		}
	}
	
	@IOValue
	@IOInstance.Order({"number", "child"})
	public static class PrivateCtor extends IOInstance.Managed<PrivateCtor>{
		private final int   number;
		private final Child child;
		private PrivateCtor(int number, Child child){
			this.number = number;
			this.child = child;
		}
		static PrivateCtor of(int number, Child child){ return new PrivateCtor(number, child); }
	}
	
	@IOInstance.Order({"number", "child"})
	public interface ReadOnlyDef extends IOInstance.Def<ReadOnlyDef>{
		int number();
		Child child();
	}
	
	private static <T extends IOInstance<T>> void checkDeepClone(T original, Child originalChild, Class<T> type){
		var struct = Struct.of(type, Struct.STATE_DONE);
		assertThat(struct.needsBuilderObj()).as("fixture must be read-only").isTrue();
		
		var copy = original.clone();
		
		assertThat(copy).isNotSameAs(original).isEqualTo(original);
		var copyChild = struct.getFields().requireExact(Child.class, "child").get(null, copy);
		assertThat(copyChild).isNotSameAs(originalChild).isEqualTo(originalChild);
		
		var builderName = struct.getConcreteType().getName() + BuilderProxyCompiler.BUILDER_PROXY_POSTFIX;
		assertThat(builderExists(builderName, struct.getConcreteType().getClassLoader()))
			.as("clone must not compile a builder").isFalse();
	}
	private static boolean builderExists(String name, ClassLoader loader){
		try{
			Class.forName(name, false, loader);
			return true;
		}catch(ClassNotFoundException e){
			return false;
		}
	}
	
	@Test
	void publicConstructor(){
		var child = new Child(7);
		checkDeepClone(new PublicCtor(42, "hello", child), child, PublicCtor.class);
	}
	
	@Test
	void packageConstructor(){
		var child = new Child(8);
		checkDeepClone(new PackageCtor(43, child), child, PackageCtor.class);
	}
	
	@Test
	void privateConstructor(){
		var child = new Child(9);
		checkDeepClone(PrivateCtor.of(44, child), child, PrivateCtor.class);
	}
	
	@IOValue
	@IOInstance.Order({"number", "child"})
	public static class DetectionProbe extends IOInstance.Managed<DetectionProbe>{
		private final int   number;
		private final Child child;
		public DetectionProbe(int number, Child child){
			this.number = number;
			this.child = child;
		}
	}
	
	@Test
	void builderDetectionWorks(){
		var struct = Struct.of(DetectionProbe.class, Struct.STATE_DONE);
		var name   = struct.getConcreteType().getName() + BuilderProxyCompiler.BUILDER_PROXY_POSTFIX;
		var loader = struct.getConcreteType().getClassLoader();
		assertThat(builderExists(name, loader)).isFalse();
		struct.getBuilderObjType(true);
		assertThat(builderExists(name, loader)).isTrue();
	}
	
	@Test
	void readOnlyDefinition(){
		var child = new Child(10);
		checkDeepClone(IOInstance.Def.of(ReadOnlyDef.class, 45, child), child, ReadOnlyDef.class);
	}
}
