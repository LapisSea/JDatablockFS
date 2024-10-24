package com.lapissea.dfs.type.def;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public sealed interface TypeDef{
	
	@IOValue
	final class Relations extends IOInstance.Managed<Relations>{
		
		private static final Relations NONE = new Relations(List.of(), null, List.of());
		
		public final List<String>      permittedSubclasses;
		@IONullability(NULLABLE)
		public final SealedParent      sealedParent;
		public final List<ClassArgDef> typeArgs;
		
		public Relations(List<String> permittedSubclasses, SealedParent sealedParent, List<ClassArgDef> typeArgs){
			this.permittedSubclasses = List.copyOf(permittedSubclasses);
			this.sealedParent = sealedParent;
			this.typeArgs = List.copyOf(typeArgs);
		}
		@Override
		public String toString(){
			if(equals(NONE)) return "NONE";
			var res = new StringJoiner(", ", "{", "}");
			if(!permittedSubclasses.isEmpty()) res.add(switch(permittedSubclasses.size()){
				case 1 -> "permits: " + permittedSubclasses;
				default -> {
					int matching = calcMatching();
					yield Iters.concat1N(
						permittedSubclasses.getFirst(),
						Iters.from(permittedSubclasses).skip(1).map(n -> n.substring(matching))
					).joinAsStr(", ", "permits: [", "]");
				}
			});
			if(sealedParent != null) res.add("parent: " + sealedParent);
			if(!typeArgs.isEmpty()) res.add("typeArgs: " + typeArgs);
			return res.toString();
		}
		private int calcMatching(){
			var minLen    = Iters.from(permittedSubclasses).mapToInt(String::length).min().orElse(0);
			var lastMaker = 0;
			for(int i = 0; i<minLen; i++){
				var c = permittedSubclasses.getFirst().charAt(i);
				if(c == '.' || c == '$') lastMaker = i;
				var i1 = i;
				if(Iters.from(permittedSubclasses).skip(1).anyMatch(name -> name.charAt(i1) != c)){
					return lastMaker;
				}
			}
			return 0;
		}
	}
	
	non-sealed interface DUnknown extends TypeDef, IOInstance.Def<DUnknown>{
		DUnknown INSTANCE = IOInstance.Def.of(DUnknown.class);
	}
	
	non-sealed interface DUnmanaged extends TypeDef, IOInstance.Def<DUnmanaged>{
		DUnmanaged INSTANCE = IOInstance.Def.of(DUnmanaged.class);
	}
	
	@IOValue
	final class DEnum extends IOInstance.Managed<DEnum> implements TypeDef{
		
		public final List<String> enumConstants;
		
		public DEnum(List<String> enumConstants){
			this.enumConstants = Objects.requireNonNull(enumConstants);
		}
	}
	
	@IOValue
	final class DJustInterface extends IOInstance.Managed<DJustInterface> implements TypeDef{
		public final Relations relations;
		
		public DJustInterface(Relations relations){ this.relations = relations; }
		
		@Override
		public Relations getRelations(){
			return relations;
		}
	}
	
	@IOValue
	final class DInstance extends IOInstance.Managed<DJustInterface> implements TypeDef{
		public final List<FieldDef> fields;
		public final List<Integer>  fieldOrder;
		public final Relations      relations;
		
		public DInstance(
			List<FieldDef> fields, List<Integer> fieldOrder, Relations relations
		){
			this.fields = List.copyOf(fields);
			this.fieldOrder = List.copyOf(fieldOrder);
			this.relations = Objects.requireNonNull(relations);
		}
		@Override
		public Relations getRelations(){
			return relations;
		}
		@Override
		public List<FieldDef> getFields(){
			return fields;
		}
	}
	
	static TypeDef of(Class<?> type){
		
		if(IOInstance.isUnmanaged(type)){
			return DUnmanaged.INSTANCE;
		}
		
		if(type.isEnum()){
			//noinspection unchecked,rawtypes
			var consts = ((Class<Enum>)type).getEnumConstants();
			return new DEnum(Iters.from(consts).map(Enum::name).toList());
		}
		
		Objects.requireNonNull(type);
		
		boolean ioInstance    = IOInstance.isInstance(type);
		boolean justInterface = !ioInstance && type.isInterface();
		
		if(justInterface){
			return new DJustInterface(computeRelations(type));
		}
		if(!ioInstance){
			return DUnknown.INSTANCE;
		}
		
		return makeInst(type);
	}
	
	private static DInstance makeInst(Class<?> type){
		Relations relations = computeRelations(type);
		if(Modifier.isAbstract(type.getModifiers()) && !UtilL.instanceOf(type, IOInstance.Def.class)){
			return new DInstance(List.of(), List.of(), relations);
		}
		
		FieldSet<?>    structFields = Struct.ofUnknown(type, Struct.STATE_FIELD_MAKE).getFields();
		List<FieldDef> fields       = structFields.mapped(FieldDef::of).toList();
		List<Integer>  fieldOrder   = IOFieldTools.computeDependencyIndex(structFields).iterIds().box().toList();
		
		return new DInstance(fields, fieldOrder, relations);
	}
	
	private static Relations computeRelations(Class<?> type){
		List<String> permits = List.of();
		if(isSealedCached(type)){
			var src = SealedUtil.getPermittedSubclasses(type);
			permits = Iters.from(src).map(Class::getName).toList();
		}
		
		SealedParent sealedParent = null;
		Class<?>     superclass   = type.getSuperclass();
		if(superclass != null && isSealedCached(superclass)){
			sealedParent = SealedParent.of(superclass.getName(), SealedParent.Type.EXTEND);
		}
		for(var inf : type.getInterfaces()){
			if(isSealedCached(inf)){
				var sp = SealedParent.of(inf.getName(), SealedParent.Type.JUST_INTERFACE);
				if(sealedParent != null){
					throw new IllegalStateException("multiple sealed parents not supported:\n" + type + "\n\t" + sealedParent + "\n\t" + sp);
				}
				sealedParent = sp;
			}
		}
		
		var typeArgs = Iters.from(type.getTypeParameters()).map(arg -> {
			var bounds = arg.getBounds();
			if(bounds.length != 1){
				throw new NotImplementedException("Multiple bounds not implemented: " + type.getName());
			}
			return ClassArgDef.of(arg.getName(), IOType.of(bounds[0]));
		}).toList();
		
		if(Objects.equals(Relations.NONE.permittedSubclasses, permits) &&
		   Objects.equals(Relations.NONE.sealedParent, sealedParent) &&
		   Objects.equals(Relations.NONE.typeArgs, typeArgs)){
			return Relations.NONE;
		}
		return new Relations(permits, sealedParent, typeArgs);
	}
	
	default Relations getRelations()  { return Relations.NONE; }
	default boolean isSealed()        { return !getRelations().permittedSubclasses.isEmpty(); }
	default List<FieldDef> getFields(){ return List.of(); }
}
