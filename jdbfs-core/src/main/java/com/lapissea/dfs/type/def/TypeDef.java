package com.lapissea.dfs.type.def;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;

import static com.lapissea.dfs.SealedUtil.isSealedCached;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

@IOValue
@IOInstance.Order({"ioInstance", "unmanaged", "justInterface", "fields", "fieldOrder", "enumConstants", "permits", "sealedParent", "typeArgs"})
public final class TypeDef extends IOInstance.Managed<TypeDef>{
	
	public final  boolean            ioInstance;
	public final  boolean            unmanaged;
	public final  boolean            justInterface;
	public final  List<FieldDef>     fields;
	public final  List<Integer>      fieldOrder;
	@IONullability(NULLABLE)
	private final List<EnumConstant> enumConstants;
	@IONullability(NULLABLE)
	private final List<String>       permits;
	@IONullability(NULLABLE)
	public final  SealedParent       sealedParent;
	public final  List<ClassArgDef>  typeArgs;
	
	public TypeDef(
		boolean ioInstance, boolean unmanaged, boolean justInterface, List<FieldDef> fields, List<Integer> fieldOrder,
		List<EnumConstant> enumConstants, List<String> permits, SealedParent sealedParent, List<ClassArgDef> typeArgs
	){
		this.ioInstance = ioInstance;
		this.unmanaged = unmanaged;
		this.justInterface = justInterface;
		this.fields = List.copyOf(fields);
		this.fieldOrder = List.copyOf(fieldOrder);
		this.enumConstants = enumConstants == null? null : List.copyOf(enumConstants);
		this.permits = permits == null? null : List.copyOf(permits);
		this.sealedParent = sealedParent;
		this.typeArgs = List.copyOf(typeArgs);
	}
	
	public static TypeDef of(Class<?> type){
		
		Objects.requireNonNull(type);
		var ioInstance = IOInstance.isInstance(type);
		var unmanaged  = IOInstance.isUnmanaged(type);
		
		List<FieldDef> fields     = List.of();
		List<Integer>  fieldOrder = List.of();
		if(ioInstance){
			if(!Modifier.isAbstract(type.getModifiers()) || UtilL.instanceOf(type, IOInstance.Def.class)){
				var structFields = Struct.ofUnknown(type, Struct.STATE_FIELD_MAKE).getFields();
				fields = structFields.mapped(FieldDef::of).toList();
				fieldOrder = IOFieldTools.computeDependencyIndex(structFields).iterIds().box().toList();
			}
		}
		
		List<EnumConstant> enumConstants = null;
		if(type.isEnum()){
			//noinspection unchecked,rawtypes
			var consts = ((Class<Enum>)type).getEnumConstants();
			enumConstants = Iters.from(consts).map(EnumConstant::of).toList();
		}
		
		List<String> permits = null;
		if(isSealedCached(type)){
			var src = SealedUtil.getPermittedSubclasses(type);
			permits = Iters.from(src).map(Class::getName).toList();
		}
		
		SealedParent sealedParent = ioInstance? getSealedParent(type) : null;
		
		var justInterface = !ioInstance && enumConstants == null && type.isInterface();
		
		List<ClassArgDef> typeArgs = getTypeArgs(type);
		
		return new TypeDef(ioInstance, unmanaged, justInterface, fields, fieldOrder, enumConstants, permits, sealedParent, typeArgs);
	}
	
	private static SealedParent getSealedParent(Class<?> type){
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
		return sealedParent;
	}
	
	private static List<ClassArgDef> getTypeArgs(Class<?> type){
		return Iters.from(type.getTypeParameters()).map(arg -> {
			var bounds = arg.getBounds();
			if(bounds.length != 1){
				throw new NotImplementedException("Multiple bounds not implemented: " + type.getName());
			}
			return ClassArgDef.of(arg.getName(), IOType.of(bounds[0]));
		}).toList();
	}
	
	public boolean isEnum()  { return enumConstants != null; }
	public boolean isSealed(){ return permits != null; }
	
	public List<String> getPermittedSubclasses(){
		return permits == null? List.of() : permits;
	}
	
	public List<EnumConstant> getEnumConstants(){
		return enumConstants == null? List.of() : enumConstants;
	}
	
	@Override
	public String toShortString(){
		return toString();
	}
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		if(ioInstance) sb.append("IO");
		if(unmanaged) sb.append("U");
		if(justInterface) sb.append("I");
		if(isSealed()){
			var permits = this.permits.toArray(String[]::new);
			if(permits.length>0){
				int startsPos = Iters.from(permits).mapToInt(String::length).min().orElse(0) - 1;
				while(startsPos>0){
					var start = permits[0].substring(0, startsPos);
					var c     = start.charAt(start.length() - 1);
					if(c == '.'){
						startsPos = 0;
						break;
					}
					if(c == '$' && Iters.from(permits).allMatch(s -> s.startsWith(start))){
						break;
					}
					startsPos--;
				}
				for(int i = 0; i<permits.length; i++){
					permits[i] = startsPos>0 && i>0? permits[i].substring(startsPos) : Utils.classNameToHuman(permits[i]);
				}
			}
			sb.append(Iters.from(permits).joinAsStr(", ", "->[", "]"));
		}
		sb.append('{');
		if(!getEnumConstants().isEmpty()){
			sb.append(Iters.from(getEnumConstants()).joinAsStr(", "));
		}
		sb.append(Iters.from(fields).joinAsStr(", ", FieldDef::toShortString));
		sb.append('}');
		
		return sb.toString();
	}
}
