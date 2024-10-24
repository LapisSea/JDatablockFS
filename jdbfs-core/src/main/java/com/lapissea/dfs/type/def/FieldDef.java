package com.lapissea.dfs.type.def;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

@IOValue
public final class FieldDef extends IOInstance.Managed<FieldDef>{
	
	public sealed interface IOAnnotation{
		@IOValue
		final class AnNullability extends Managed<AnNullability> implements IOAnnotation{
			public final IONullability.Mode mode;
			public AnNullability(IONullability.Mode mode){ this.mode = mode; }
		}
		
		@IOValue
		final class AnReferenceType extends Managed<AnReferenceType> implements IOAnnotation{
			public final IOValue.Reference.PipeType type;
			public AnReferenceType(IOValue.Reference.PipeType type){ this.type = type; }
		}
		
		sealed interface AnDependencies extends IOAnnotation{
			@IOValue
			final class Single extends Managed<Single> implements AnDependencies{
				public final String name;
				public Single(String name){ this.name = name; }
				@Override
				public List<String> names(){ return List.of(name); }
			}
			
			@IOValue
			final class Multi extends Managed<Multi> implements AnDependencies{
				public final List<String> names;
				public Multi(List<String> names){ this.names = List.copyOf(names); }
				@Override
				public List<String> names(){ return names; }
			}
			List<String> names();
		}
		
		
		final class AnGeneric extends Managed<AnGeneric> implements IOAnnotation{ }
		
		final class AnUnsigned extends Managed<AnUnsigned> implements IOAnnotation{ }
		
		final class AnUnsafe extends Managed<AnUnsafe> implements IOAnnotation{ }
		
	}
	
	public final IOType             type;
	public final String             name;
	public final List<IOAnnotation> annotations;
	
	public FieldDef(IOType type, String name, List<IOAnnotation> annotations){
		this.type = Objects.requireNonNull(type);
		this.name = Objects.requireNonNull(name);
		this.annotations = List.copyOf(annotations);
		
		if(DEBUG_VALIDATION) checkAnnotations(annotations);
	}
	
	private void checkAnnotations(List<IOAnnotation> annotations){
		if(Iters.from(annotations).map(Object::getClass).hasDuplicates()){
			throw new IllegalArgumentException("Annotations must be unique! annotations: " + annotations);
		}
	}
	
	public static FieldDef of(IOField<?, ?> field){
		var type = IOType.of(field.getAccessor().getGenericType(null));
		var name = field.getName();
		
		var annotations = new ArrayList<IOAnnotation>();
		
		var isDynamic = IOFieldTools.isGeneric(field);
		
		IOFieldTools.getNullabilityOpt(field)
		            .map(IOAnnotation.AnNullability::new)
		            .ifPresent(annotations::add);
		
		if(isDynamic) annotations.add(new IOAnnotation.AnGeneric());
		
		field.getAccessor().getAnnotation(IOValue.Reference.class)
		     .map(IOValue.Reference::dataPipeType)
		     .map(IOAnnotation.AnReferenceType::new)
		     .ifPresent(annotations::add);
		
		var depNames = field.getDependencies().iter().toModList(IOField::getName);
		if(field.getType().isArray()) depNames.remove(FieldNames.collectionLen(field.getAccessor()));
		if(isDynamic) depNames.remove(FieldNames.genericID(field.getAccessor()));
		switch(depNames.size()){
			case 0 -> { }
			case 1 -> annotations.add(new IOAnnotation.AnDependencies.Single(depNames.getFirst()));
			default -> annotations.add(new IOAnnotation.AnDependencies.Multi(depNames));
		}
		
		if(field.getAccessor().hasAnnotation(IOValue.Unsigned.class)) annotations.add(new IOAnnotation.AnUnsigned());
		if(field.getAccessor().hasAnnotation(IOUnsafeValue.class)) annotations.add(new IOAnnotation.AnUnsafe());
		
		return new FieldDef(type, name, annotations);
	}
	
	@Override
	public String toString(){
		if(type == null) return getClass().getSimpleName() + IOFieldTools.UNINITIALIZED_FIELD_SIGN;
		
		var nullNote = Iters.from(annotations).instancesOf(IOAnnotation.AnNullability.class).findFirst()
		                    .map(n -> " " + n.mode).orElse("");
		var depsNote = Iters.from(annotations).instancesOf(IOAnnotation.AnDependencies.class).findFirst()
		                    .map(n -> "(deps = [" + String.join(", ", n.names()) + "])").orElse("");
		
		return "{" + name + nullNote + ": " + type + depsNote + "}";
	}
	@Override
	public String toShortString(){
		var nullNote = Iters.from(annotations).instancesOf(IOAnnotation.AnNullability.class).findFirst()
		                    .map(n -> " " + n.mode).orElse("");
		
		return "{" + name + nullNote + ": " + Utils.toShortString(type) + "}";
	}
	
	public String getName(){
		return name;
	}
}
