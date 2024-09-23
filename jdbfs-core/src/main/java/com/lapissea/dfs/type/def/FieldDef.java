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

import java.util.List;
import java.util.Objects;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

@IOValue
@IOInstance.Order({"type", "name", "isDynamic", "unsigned", "unsafe", "dependencies", "referenceType", "nullability"})
public final class FieldDef extends IOInstance.Managed<FieldDef>{
	
	public final IOType       type;
	public final String       name;
	public final boolean      isDynamic;
	public final boolean      unsigned;
	public final boolean      unsafe;
	public final List<String> dependencies;
	
	@IONullability(NULLABLE)
	public final IOValue.Reference.PipeType referenceType;
	public final IONullability.Mode         nullability;
	
	public FieldDef(IOType type, String name, boolean isDynamic, boolean unsigned, boolean unsafe, List<String> dependencies, IOValue.Reference.PipeType referenceType, IONullability.Mode nullability){
		this.type = Objects.requireNonNull(type);
		this.name = Objects.requireNonNull(name);
		this.isDynamic = isDynamic;
		this.unsigned = unsigned;
		this.unsafe = unsafe;
		this.dependencies = List.copyOf(dependencies);
		this.referenceType = referenceType;
		this.nullability = Objects.requireNonNull(nullability);
	}
	
	public static FieldDef of(IOField<?, ?> field){
		var type          = IOType.of(field.getAccessor().getGenericType(null));
		var name          = field.getName();
		var nullability   = IOFieldTools.getNullability(field);
		var isDynamic     = IOFieldTools.isGeneric(field);
		var referenceType = field.getAccessor().getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElse(null);
		var deps          = field.getDependencies().iter().toModSet(IOField::getName);
		if(field.getType().isArray()) deps.remove(FieldNames.collectionLen(field.getAccessor()));
		if(isDynamic) deps.remove(FieldNames.genericID(field.getAccessor()));
		var dependencies = List.copyOf(deps);
		var unsigned     = field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
		var unsafe       = field.getAccessor().hasAnnotation(IOUnsafeValue.class);
		return new FieldDef(type, name, isDynamic, unsigned, unsafe, dependencies, referenceType, nullability);
	}
	
	@Override
	public String toString(){
		if(type == null) return getClass().getSimpleName() + IOFieldTools.UNINITIALIZED_FIELD_SIGN;
		return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability : "") + ": " + type + (dependencies.isEmpty()? "" : "(deps = [" + String.join(", ", dependencies) + "])");
	}
	@Override
	public String toShortString(){
		return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability.shortName : "") + ": " + Utils.toShortString(type);
	}
}
