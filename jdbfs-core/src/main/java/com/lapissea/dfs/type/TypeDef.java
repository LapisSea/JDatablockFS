package com.lapissea.dfs.type;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.IterableIntPP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

@IOValue
public final class TypeDef extends IOInstance.Managed<TypeDef>{
	
	@IOValue
	@Def.Order({"name", "bound"})
	public interface ClassArgDef extends IOInstance.Def<ClassArgDef>{
		String name();
		IOType bound();
		
		static ClassArgDef of(String name, IOType bound){
			return Def.of(ClassArgDef.class, name, bound);
		}
	}
	
	@IOValue
	public static final class FieldDef extends IOInstance.Managed<FieldDef>{
		private IOType   type;
		private String   name;
		private boolean  isDynamic;
		private boolean  unsigned;
		private boolean  unsafe;
		private String[] dependencies;
		
		@IONullability(NULLABLE)
		private IOValue.Reference.PipeType referenceType;
		private IONullability.Mode         nullability;
		
		public FieldDef(){ }
		
		public FieldDef(IOField<?, ?> field){
			type = IOType.of(field.getAccessor().getGenericType(null));
			name = field.getName();
			nullability = IOFieldTools.getNullability(field);
			isDynamic = IOFieldTools.isGeneric(field);
			referenceType = field.getAccessor().getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElse(null);
			var deps = field.getDependencies().collectToSet(IOField::getName);
			if(field.getType().isArray()) deps.remove(FieldNames.collectionLen(field.getAccessor()));
			if(isDynamic) deps.remove(FieldNames.genericID(field.getAccessor()));
			dependencies = deps.toArray(String[]::new);
			unsigned = field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
			unsafe = field.getAccessor().hasAnnotation(IOUnsafeValue.class);
		}
		
		public IOType getType()    { return type; }
		public String getName()    { return name; }
		public boolean isDynamic() { return isDynamic; }
		public boolean isUnsigned(){ return unsigned; }
		public boolean isUnsafe()  { return unsafe; }
		
		public List<String> getDependencies(){
			return dependencies == null? List.of() : ArrayViewList.create(dependencies, null);
		}
		
		public IONullability.Mode getNullability()          { return nullability; }
		public IOValue.Reference.PipeType getReferenceType(){ return referenceType; }
		
		@Override
		public String toString(){
			if(type == null) return getClass().getSimpleName() + "<uninitialized>";
			return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability : "") + ": " + type + (dependencies == null || dependencies.length == 0? "" : "(deps = [" + String.join(", ", dependencies) + "])");
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
		
		public static SealedParent of(String name, Type type){
			return IOInstance.Def.of(SealedParent.class, name, type);
		}
		
		String name();
		Type type();
	}
	
	
	private boolean           ioInstance;
	private boolean           unmanaged;
	private boolean           justInterface;
	private FieldDef[]        fields     = new FieldDef[0];
	private int[]             fieldOrder = new int[0];
	@IONullability(NULLABLE)
	private EnumConstant[]    enumConstants;
	@IONullability(NULLABLE)
	private String[]          permits;
	@IONullability(NULLABLE)
	private SealedParent      sealedParent;
	private List<ClassArgDef> typeArgs   = List.of();
	
	public TypeDef(){ }
	
	public TypeDef(@NotNull Class<?> type){
		Objects.requireNonNull(type);
		ioInstance = IOInstance.isInstance(type);
		unmanaged = IOInstance.isUnmanaged(type);
		if(ioInstance){
			if(!Modifier.isAbstract(type.getModifiers()) || UtilL.instanceOf(type, IOInstance.Def.class)){
				var structFields = Struct.ofUnknown(type, Struct.STATE_FIELD_MAKE).getFields();
				fields = structFields.map(FieldDef::new).toArray(FieldDef[]::new);
				fieldOrder = IOFieldTools.computeDependencyIndex(structFields).iterIds().toArray();
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
		if(isSealedCached(type)){
			var src = SealedUtil.getPermittedSubclasses(type);
			permits = new String[src.size()];
			for(int i = 0; i<src.size(); i++){
				permits[i] = src.get(i).getName();
			}
		}
		if(ioInstance){
			var s = type.getSuperclass();
			if(s != null && isSealedCached(s)){
				setSealedParent(SealedParent.of(s.getName(), SealedParent.Type.EXTEND));
			}
			for(var inf : type.getInterfaces()){
				if(isSealedCached(inf)){
					setSealedParent(SealedParent.of(inf.getName(), SealedParent.Type.JUST_INTERFACE));
				}
			}
		}
		if(!isIoInstance() && !isEnum() && type.isInterface()){
			justInterface = true;
		}
		
		var typeArgs = type.getTypeParameters();
		this.typeArgs = new ArrayList<>(typeArgs.length);
		for(var arg : typeArgs){
			var bounds = arg.getBounds();
			if(bounds.length != 1){
				throw new NotImplementedException("Multiple bounds not implemented: " + type.getName());
			}
			this.typeArgs.add(ClassArgDef.of(arg.getName(), IOType.of(bounds[0])));
		}
	}
	
	private void setSealedParent(SealedParent sealedParent){
		if(this.sealedParent != null){
			throw new IllegalStateException("multiple sealed parents not supported:\n" + this + "\n\t" + this.sealedParent + "\n\t" + sealedParent);
		}
		this.sealedParent = sealedParent;
	}
	
	public boolean isUnmanaged()    { return unmanaged; }
	public boolean isIoInstance()   { return ioInstance; }
	public boolean isEnum()         { return enumConstants != null; }
	public boolean isSealed()       { return permits != null; }
	public boolean isJustInterface(){ return justInterface; }
	
	public IterableIntPP getFieldOrder(){
		return Iters.ofInts(fieldOrder);
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
	
	public List<ClassArgDef> getTypeArgs(){
		return Collections.unmodifiableList(typeArgs);
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
				int startsPos = Iters.of(permits).mapToInt(String::length).min().orElse(0) - 1;
				while(startsPos>0){
					var start = permits[0].substring(0, startsPos);
					var c     = start.charAt(start.length() - 1);
					if(c == '.'){
						startsPos = 0;
						break;
					}
					if(c == '$' && Iters.of(permits).allMatch(s -> s.startsWith(start))){
						break;
					}
					startsPos--;
				}
				for(int i = 0; i<permits.length; i++){
					permits[i] = startsPos>0 && i>0? permits[i].substring(startsPos) : Utils.classNameToHuman(permits[i]);
				}
			}
			sb.append(Iters.of(permits).joinAsStr(", ", "->[", "]"));
		}
		sb.append('{');
		if(!getEnumConstants().isEmpty()){
			sb.append(Iters.from(getEnumConstants()).joinAsStr(", "));
		}
		sb.append(Iters.of(fields).joinAsStr(", ", FieldDef::toShortString));
		sb.append('}');
		
		return sb.toString();
	}
}
