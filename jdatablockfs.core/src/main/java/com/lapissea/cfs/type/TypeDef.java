package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class TypeDef extends IOInstance.Managed<TypeDef>{
	
	public static final class FieldDef extends IOInstance.Managed<FieldDef>{
		@IOValue
		private TypeLink type;
		
		@IOValue
		private String name;
		
		@IOValue
		private boolean isDynamic;
		
		@IOValue
		private boolean unsigned;
		
		@IOValue
		private String[] dependencies;
		
		@IOValue
		private IONullability.Mode nullability;
		
		@IOValue
		@IONullability(NULLABLE)
		private IOValue.Reference.PipeType referenceType;
		
		public FieldDef(){ }
		
		public FieldDef(IOField<?, ?> field){
			type = TypeLink.of(field.getAccessor().getGenericType(null));
			name = field.getName();
			nullability = IOFieldTools.getNullability(field);
			isDynamic = IOFieldTools.isGeneric(field);
			referenceType = field.getAccessor().getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElse(null);
			var deps = field.dependencyStream().map(IOField::getName).collect(Collectors.toSet());
			if(field.getType().isArray()) deps.remove(IOFieldTools.makeCollectionLenName(field.getAccessor()));
			if(isDynamic) deps.remove(IOFieldTools.makeGenericIDFieldName(field.getAccessor()));
			dependencies = deps.toArray(String[]::new);
			unsigned = field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
		}
		
		public TypeLink getType()  { return type; }
		public String getName()    { return name; }
		public boolean isDynamic() { return isDynamic; }
		public boolean isUnsigned(){ return unsigned; }
		
		public List<String> getDependencies(){
			return dependencies == null? List.of() : ArrayViewList.create(dependencies, null);
		}
		
		public IONullability.Mode getNullability()          { return nullability; }
		public IOValue.Reference.PipeType getReferenceType(){ return referenceType; }
		
		@Override
		public String toString(){
			return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability : "") + ": " + type.toString() + (dependencies == null || dependencies.length == 0? "" : "(deps = [" + String.join(", ", dependencies) + "])");
		}
		@Override
		public String toShortString(){
			return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability.shortName : "") + ": " + Utils.toShortString(type);
		}
	}
	
	@IOInstance.Def.ToString(name = false, fNames = false, curly = false)
	public interface EnumConstant extends IOInstance.Def<EnumConstant>{
		
		private static EnumConstant of(Enum<?> e){
			return IOInstance.Def.of(EnumConstant.class, e.name());
		}
		
		String getName();
	}
	
	@IOInstance.Def.ToString(name = false, fNames = false)
	@IOInstance.Def.Order({"name", "type"})
	public interface SealedParent extends IOInstance.Def<SealedParent>{
		enum Type{
			EXTEND,
			JUST_INTERFACE
		}
		
		private static SealedParent of(String name, Type type){
			return IOInstance.Def.of(SealedParent.class, name, type);
		}
		
		String name();
		Type type();
	}
	
	
	@IOValue
	private boolean ioInstance, unmanaged, justInterface;
	
	@IOValue
	private FieldDef[] fields = new FieldDef[0];
	
	@IOValue
	private int[] fieldOrder = new int[0];
	
	@IOValue
	@IONullability(NULLABLE)
	private EnumConstant[] enumConstants;
	
	@IOValue
	@IONullability(NULLABLE)
	private String[] permits;
	
	@IOValue
	@IONullability(NULLABLE)
	private SealedParent sealedParent;
	
	public TypeDef(){ }
	
	public TypeDef(@NotNull Class<?> type){
		Objects.requireNonNull(type);
		ioInstance = IOInstance.isInstance(type);
		unmanaged = IOInstance.isUnmanaged(type);
		if(ioInstance){
			if(!Modifier.isAbstract(type.getModifiers()) || UtilL.instanceOf(type, IOInstance.Def.class)){
				var structFields = Struct.ofUnknown(type).getFields();
				fields = structFields.stream().map(FieldDef::new).toArray(FieldDef[]::new);
				fieldOrder = IOFieldTools.computeDependencyIndex(structFields).stream().toArray();
			}
		}
		if(type.isEnum()){
			//noinspection unchecked,rawtypes
			var consts = ((Class<Enum>)type).getEnumConstants();
			var res    = new EnumConstant[consts.length];
			for(int i = 0; i<consts.length; i++){
				var cons = consts[i];
				res[i] = EnumConstant.of(cons);
			}
			enumConstants = res;
		}
		if(type.isSealed()){
			var src = type.getPermittedSubclasses();
			permits = new String[src.length];
			for(int i = 0; i<src.length; i++){
				permits[i] = src[i].getName();
			}
		}
		if(ioInstance){
			var s = type.getSuperclass();
			if(s != null && s.isSealed()){
				setSealedParent(SealedParent.of(s.getName(), SealedParent.Type.EXTEND));
			}
			for(var inf : type.getInterfaces()){
				if(inf.isSealed()){
					setSealedParent(SealedParent.of(inf.getName(), SealedParent.Type.JUST_INTERFACE));
				}
			}
		}
		if(!isIoInstance() && !isEnum() && type.isInterface()){
			justInterface = true;
		}
	}
	
	private void setSealedParent(SealedParent sealedParent){
		if(this.sealedParent != null){
			throw new IllegalStateException("multiple sealed parents not supported:\n" + this.sealedParent + "\n" + sealedParent);
		}
		this.sealedParent = sealedParent;
	}
	
	public boolean isUnmanaged()    { return unmanaged; }
	public boolean isIoInstance()   { return ioInstance; }
	public boolean isEnum()         { return enumConstants != null; }
	public boolean isSealed()       { return permits != null; }
	public boolean isJustInterface(){ return justInterface; }
	
	public IntStream getFieldOrder(){
		return Arrays.stream(fieldOrder);
	}
	public List<String> getPermittedSubclasses(){
		if(permits == null) return List.of();
		return ArrayViewList.create(permits, null);
	}
	
	public SealedParent getSealedParent(){
		return sealedParent;
	}
	
	public List<FieldDef> getFields(){
		if(fields == null) return List.of();
		return ArrayViewList.create(fields, null);
	}
	
	public List<EnumConstant> getEnumConstants(){
		if(!isEnum()) return List.of();
		return ArrayViewList.create(enumConstants, null);
	}
	@Override
	public String toShortString(){
		return toString();
	}
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		if(isIoInstance()) sb.append("IO");
		if(isUnmanaged()) sb.append("U");
		if(isJustInterface()) sb.append("I");
		if(isSealed()){
			var permits = this.permits.clone();
			if(permits.length>0){
				int startsPos = Arrays.stream(permits).mapToInt(String::length).min().orElse(0) - 1;
				while(startsPos>0){
					var start = permits[0].substring(0, startsPos);
					var c     = start.charAt(start.length() - 1);
					if(c == '.'){
						startsPos = 0;
						break;
					}
					if(c == '$' && Arrays.stream(permits).allMatch(s -> s.startsWith(start))){
						break;
					}
					startsPos--;
				}
				for(int i = 0; i<permits.length; i++){
					permits[i] = startsPos>0 && i>0? permits[i].substring(startsPos) : Utils.classNameToHuman(permits[i], false);
				}
			}
			sb.append(Arrays.stream(permits).collect(Collectors.joining(", ", "->[", "]")));
		}
		sb.append('{');
		if(!getEnumConstants().isEmpty()){
			sb.append(getEnumConstants().stream().map(Objects::toString).collect(Collectors.joining(", ")));
		}
		sb.append(Arrays.stream(fields).map(FieldDef::toShortString).collect(Collectors.joining(", ")));
		sb.append('}');
		
		return sb.toString();
	}
}
