package com.lapissea.dfs.type.field;

import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.CodeUtils;
import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.CodeArg;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface SpecializedGenerator{
	
	interface OnBitSpace<T extends IOInstance<T>> extends SpecializedGenerator{
		void injectReadFieldFromBits(CodeBlock body, AccessMap accessMap, String bitsFieldName) throws MalformedJorth, UnsupportedCodeGenType;
		
		SizeDescriptor<T> getSizeDescriptor();
		FieldAccessor<T> getAccessor();
		
		@Override
		default void injectReadField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			var bits  = Math.toIntExact(getSizeDescriptor().requireFixed(WordSpace.BIT));
			var bytes = BitUtils.bitsToBytes(bits);
			var name  = accessMap.temporaryLocalField(bits<32? int.class : long.class, body);
			
			CodeUtils.readBytesFromSrc(body, bytes);
			CodeUtils.rawBitsToValidatedBits(body, bytes, bits);
			if(bits<32){
				body.cast(int.class);
			}
			body.set(name);
			
			injectReadFieldFromBits(body, accessMap, name);
			
		}
	}
	
	final class AccessMap{
		
		private record GetInfo(ClassName className, String fieldName){ }
		
		private final Map<FieldAccessor<?>, String>            localFields    = new HashMap<>();
		private final Map<FieldAccessor<?>, AccessMap.GetInfo> accessorFields = new HashMap<>();
		private final Map<IOField<?, ?>, AccessMap.GetInfo>    fieldRefFields = new HashMap<>();
		private final Map<Class<?>, AccessMap.GetInfo>         enumArrays     = new HashMap<>();
		
		private int tmpFieldCount;
		
		private final ClassDefinition cw;
		private       int             constantIndex;
		
		private final List<Set<String>> temporaryStack = new ArrayList<>();
		
		/// indicates that if any field requires the (var) io pool
		private boolean hasIOPool;
		/// indicates that the object being built has its fields in local variables, not a builder
		private boolean localObject;
		
		public AccessMap(ClassDefinition cw){ this.cw = cw; }
		
		public void setup(boolean hasIOPool, boolean localObject){
			this.hasIOPool = hasIOPool;
			this.localObject = localObject;
			tmpFieldCount = 0;
			localFields.clear();
			temporaryStack.clear();
		}
		
		public void set(FieldAccessor<?> field, CodeBlock body, CodeArg args) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(!localObject){
						body.dup();
						switch(fom.setter()){
							case FieldAccessor.FieldOrMethod.AccessType.Field(var declaringClass, var name) -> {
								args.accept(body);
								body.set(declaringClass, name);
							}
							case FieldAccessor.FieldOrMethod.AccessType.Method(var name) -> {
								body.call(name, args);
							}
						}
					}else{
						args.accept(body);
						
						if(!localFields.containsKey(field)){
							var name = "initVal_" + field.getName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
							localFields.put(field, name);
							body.var(field.getType(), name);
						}
						var localFieldName = localFields.get(field);
						body.set(localFieldName);
					}
				}
				case VirtualAccessor<?> virutal -> {
					args.accept(body);
					
					if(!localFields.containsKey(field)){
						var name = "virt_" + field.getName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
						localFields.put(field, name);
						body.var(field.getType(), name);
					}
					
					var localFieldName = localFields.get(field);
					body.set(localFieldName);
					
					if(hasIOPool){
						var    accessorInfo = getOrCreateAccessorInfo(field);
						String fnName;
						if(field.getType() == long.class) fnName = "setLong";
						else if(field.getType() == int.class) fnName = "setInt";
						else if(field.getType() == boolean.class) fnName = "setBoolean";
						else if(field.getType() == byte.class) fnName = "setByte";
						else fnName = "set";
						
						body.get("ioPool")
						    .call(fnName, a -> a.get(accessorInfo.className, accessorInfo.fieldName)
						                        .get(localFieldName));
					}
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		
		private GetInfo getOrCreateAccessorInfo(FieldAccessor<?> field) throws MalformedJorth{
			var accessorInfo = accessorFields.get(field);
			if(accessorInfo == null){
				return createFieldAccessorConstant(field);
			}
			return accessorInfo;
		}
		
		private void createEnumConstant(Class<? extends Enum<?>> type) throws MalformedJorth{
			var name = "eArr_" + (constantIndex++) + "_" + type.getSimpleName().replaceAll("[^A-Za-z]", "");
			cw.field(type.arrayType(), name)
			  .visibility(Visibility.PRIVATE).staticFinal(e -> e.call(type, "values"));
			
			addEnumArray(type, cw.getTypeDef("ThisClass"), name);
		}
		
		private GetInfo createFieldAccessorConstant(FieldAccessor<?> accessor) throws MalformedJorth{
			var name = "acc_" + (constantIndex++) + "_" + accessor.getName().replaceAll("[^A-Za-z]", "");
			
			var field = cw.field(GenericType.of(VirtualAccessor.class).withArgs(cw.getTypeDef("ObjType")), name)
			              .visibility(Visibility.PRIVATE).staticFinal();
			
			var cinit = cw.staticInit().body();
			getObjField(cinit, cw, accessor.getName());
			cinit.call("getAccessor").cast(VirtualAccessor.class)
			     .setField(field);
			
			return addAccessorField(accessor, cw.getTypeDef("ThisClass"), name);
		}
		private GetInfo createFieldRefConstant(IOField<?, ?> ioField) throws MalformedJorth{
			var name = "fieldRef_" + (constantIndex++) + "_" + ioField.getName().replaceAll("[^A-Za-z]", "");
			
			var field = cw.field(GenericType.of(IOField.class).withArgs(cw.getTypeDef("ObjType")), name)
			              .visibility(Visibility.PRIVATE).staticFinal();
			
			var cinit = cw.staticInit().body();
			getObjField(cinit, cw, ioField.getName());
			cinit.setField(field);
			
			return addFieldRefField(ioField, cw.getTypeDef("ThisClass"), name);
		}
		
		private static void getObjField(CodeBlock cinit, ClassDefinition cw, String name) throws MalformedJorth{
			getObjFields(cinit, cw);
			cinit.call("requireByName", e -> e.val(name));
		}
		private static void getObjFields(CodeBlock cinit, ClassDefinition cw) throws MalformedJorth{
			if(!cinit.hasVar("objFields")){
				cinit.call(Struct.class, "of", e -> e.val(cw.getTypeDef("ObjType")).val(Struct.STATE_FIELD_MAKE))
				     .call("getFields")
				     .setIntoNewVar("objFields");
			}
			cinit.get("objFields");
		}
		
		public <E extends Enum<E>> void getEnumArray(Class<E> type, CodeBlock code) throws MalformedJorth{
			var info = enumArrays.get(type);
			if(info == null){
				createEnumConstant(type);
				info = Objects.requireNonNull(enumArrays.get(type));
			}
			code.get(info.className, info.fieldName);
		}
		
		public <E extends Enum<E>> void getFieldRef(IOField<?, ?> field, CodeBlock block) throws MalformedJorth{
			var info = fieldRefFields.get(field);
			if(info == null){
				info = createFieldRefConstant(field);
			}
			block.get(info.className, info.fieldName);
		}
		
		public void get(IOField<?, ?> field, CodeBlock body) throws MalformedJorth{
			get(field.getAccessor(), body);
		}
		public void get(FieldAccessor<?> field, CodeBlock body) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(localObject){
						var localFieldName = localFields.get(field);
						Objects.requireNonNull(localFieldName);
						body.get(localFieldName);
						return;
					}
					
					switch(fom.setter()){
						case FieldAccessor.FieldOrMethod.AccessType.Field(var declaringClass, var name) -> {
							body.dup()
							    .get(declaringClass, name);
						}
						case FieldAccessor.FieldOrMethod.AccessType.Method(var name) -> {
							throw new NotImplementedException("call method");
						}
					}
				}
				case VirtualAccessor<?> virutal -> {
					var name = localFields.get(field);
					if(name == null) throw new MalformedJorth("Local field " + field.getName() + " does not exist");
					body.get(name);
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		private AccessMap.GetInfo addAccessorField(FieldAccessor<?> accessor, ClassName className, String fieldName){
			var info = new AccessMap.GetInfo(className, fieldName);
			accessorFields.put(accessor, info);
			return info;
		}
		private GetInfo addFieldRefField(IOField<?, ?> accessor, ClassName className, String fieldName){
			var info = new AccessMap.GetInfo(className, fieldName);
			fieldRefFields.put(accessor, info);
			return info;
		}
		public void addEnumArray(Class<?> type, ClassName className, String fieldName){
			enumArrays.put(type, new AccessMap.GetInfo(className, fieldName));
		}
		
		public String temporaryLocalField(Class<?> type, CodeBlock code) throws MalformedJorth{
			var name = "tmp_" + type.getSimpleName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
			code.var(type, name);
			tmpFieldCount++;
			if(!temporaryStack.isEmpty()){
				temporaryStack.getLast().add(name);
			}
			return name;
		}
		
		public void markTemporary(){
			temporaryStack.add(new HashSet<>());
		}
		public void dropTemporary(CodeBlock body) throws MalformedJorth{
			for(String field : temporaryStack.removeLast()){
				body.forgetVar(field);
			}
		}
		
		private int uniqueCounter(){
			return localFields.size() + tmpFieldCount;
		}
	}
	
	void injectReadField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType;
}
