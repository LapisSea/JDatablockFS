package com.lapissea.dfs.type.field;

import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.instancepipe.PipeCodeGen;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
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
import com.lapissea.util.ShouldNeverHappenError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface SpecializedGenerator{
	
	interface OnBitSpace<T extends IOInstance<T>> extends SpecializedGenerator{
		void injectReadFieldFromBits(CodeBlock body, AccessMap accessMap, String bitsFieldName) throws MalformedJorth, UnsupportedCodeGenType;
		default void injectWriteFieldToBits(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			throw new UnsupportedCodeGenType("Bit writing is not supported: " + getAccessor());
		}
		@Override
		default void injectWriteField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			int bits  = Math.toIntExact(getSizeDescriptor().requireFixed(WordSpace.BIT));
			int bytes = BitUtils.bitsToBytes(bits);
			body.get("dest");
			injectWriteFieldToBits(body, accessMap);
			if(bytes*8 != bits) body.val(BitUtils.makeMask(bytes*8)^BitUtils.makeMask(bits)).bitOr();
			body.val(bytes).call("writeWord", 2);
		}
		@Override
		default void injectSkipField(CodeBlock body, AccessMap accessMap) throws MalformedJorth{
			int bits = Math.toIntExact(getSizeDescriptor().requireFixed(WordSpace.BIT));
			body.get("src").call("skipExact", args -> args.val((long)BitUtils.bitsToBytes(bits)));
		}
		
		SizeDescriptor<T> getSizeDescriptor();
		FieldAccessor<T> getAccessor();
		
		@Override
		default void injectReadField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
			var bits  = Math.toIntExact(getSizeDescriptor().requireFixed(WordSpace.BIT));
			var bytes = BitUtils.bitsToBytes(bits);
			var field = accessMap.temporaryLocalField(bits<32? int.class : long.class, "fieldBits", body);
			
			CodeUtils.readBytesFromSrc(body, bytes);
			CodeUtils.rawBitsToValidatedBits(body, bytes, bits);
			if(bits<32){
				body.cast(int.class);
			}
			body.set(field);
			
			injectReadFieldFromBits(body, accessMap, field);
			
		}
	}
	
	final class AccessMap{
		
		private record GetInfo(ClassName className, String fieldName){
			void get(CodeBlock block) throws MalformedJorth{
				block.get(className, fieldName);
			}
		}
		
		private final Map<FieldAccessor<?>, String>            localFields    = new HashMap<>();
		private final Map<FieldAccessor<?>, AccessMap.GetInfo> accessorFields = new HashMap<>();
		private final Map<IOField<?, ?>, AccessMap.GetInfo>    fieldRefFields = new IdentityHashMap<>();
		private final Map<Class<?>, AccessMap.GetInfo>         enumArrays     = new HashMap<>();
		private final Map<StructPipe<?>, AccessMap.GetInfo>    pipeRefs       = new IdentityHashMap<>();
		private final Map<Object, AccessMap.GetInfo>           constantRefs   = new IdentityHashMap<>();
		
		private int tmpFieldCount;
		
		private final ClassDefinition cw;
		private       int             constantIndex;
		private final List<Object>    constants;
		
		private final List<Set<String>> temporaryStack = new ArrayList<>();
		
		/// indicates that if any field requires the (var) io pool
		private boolean hasIOPool;
		/// indicates that field values are held in locals until constructor invocation
		private boolean constructorFields;
		private boolean existingValues;
		private String  objectLocal;
		
		public AccessMap(ClassDefinition cw, List<Object> constants){
			this.cw = cw;
			this.constants = constants;
		}
		
		public void setup(boolean hasIOPool, boolean constructorFields){
			this.hasIOPool = hasIOPool;
			this.constructorFields = constructorFields;
			existingValues = false;
			objectLocal = null;
			tmpFieldCount = 0;
			localFields.clear();
			temporaryStack.clear();
		}
		
		public void setupForWrite(){
			setup(true, false);
			existingValues = true;
		}
		
		public void captureObject(CodeBlock body) throws MalformedJorth{
			if(constructorFields) throw new ShouldNeverHappenError("No object to capture when using local fields");
			if(objectLocal != null) throw new ShouldNeverHappenError("Object already captured");
			
			objectLocal = "readObject_" + uniqueCounter();
			body.var(GenericType.of(body.getTypeDef("ObjType")), objectLocal)
			    .set(objectLocal);
		}
		
		public void getObject(CodeBlock body) throws MalformedJorth{
			if(constructorFields) throw new ShouldNeverHappenError("Constructing object does not exist till final constructor call");
			body.get(objectLocal);
		}
		
		public void set(FieldAccessor<?> field, CodeBlock body, CodeArg args) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(!constructorFields){
						body.get(objectLocal);
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
					
					if(virutal.getStoragePool() == StoragePool.INSTANCE){
						if(!constructorFields) storeInstanceField(virutal, localFieldName, objectLocal, body);
					}else if(hasIOPool){
						var accessor = getOrCreateAccessorInfo(field);
						body.call(PipeCodeGen.class, "setAccessorValue", values -> {
							accessor.get(values);
							values.get("ioPool").nullVal(IOInstance.class).get(localFieldName);
							if(field.getType().isPrimitive()) values.box();
						});
					}
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		
		private void storeInstanceField(VirtualAccessor<?> field, String local, String object, CodeBlock body) throws MalformedJorth{
			var accessor = getOrCreateAccessorInfo(field);
			body.call(PipeCodeGen.class, "setAccessorValue", args -> {
				accessor.get(args);
				args.nullVal(VarPool.class).get(object).get(local);
				if(field.getType().isPrimitive()) args.box();
			});
		}
		
		public void storeConstructedInstanceFields(CodeBlock body) throws MalformedJorth{
			if(localFields.keySet().stream().noneMatch(f -> f instanceof VirtualAccessor<?> v && v.getStoragePool() == StoragePool.INSTANCE)) return;
			String object = "constructedObject_" + uniqueCounter();
			body.setIntoNewVar(object);
			for(var entry : localFields.entrySet()){
				if(entry.getKey() instanceof VirtualAccessor<?> virtual && virtual.getStoragePool() == StoragePool.INSTANCE){
					storeInstanceField(virtual, entry.getValue(), object, body);
				}
			}
			body.get(object);
		}
		
		private GetInfo getOrCreateAccessorInfo(FieldAccessor<?> field) throws MalformedJorth{
			var accessorInfo = accessorFields.get(field);
			if(accessorInfo == null){
				return createFieldAccessorConstant(field);
			}
			return accessorInfo;
		}
		
		private GetInfo createEnumConstant(Class<? extends Enum<?>> type) throws MalformedJorth{
			var name = "eArr_" + (constantIndex++) + "_" + type.getSimpleName().replaceAll("[^A-Za-z]", "");
			cw.field(type.arrayType(), name)
			  .visibility(Visibility.PRIVATE).staticFinal(e -> e.call(type, "values"));
			
			ClassName className = cw.getTypeDef("ThisClass");
			GetInfo   info      = new GetInfo(className, name);
			enumArrays.put(type, info);
			return info;
		}
		
		private GetInfo createFieldAccessorConstant(FieldAccessor<?> accessor) throws MalformedJorth{
			var name = "acc_" + (constantIndex++) + "_" + accessor.getName().replaceAll("[^A-Za-z]", "");
			
			var field = cw.field(Object.class, name)
			              .visibility(Visibility.PRIVATE).staticFinal();
			
			var cinit = cw.staticInit().body();
			PipeCodeGen.loadConstant(cinit, constants, accessor, Object.class);
			cinit.setField(field);
			
			ClassName className = cw.getTypeDef("ThisClass");
			GetInfo   info      = new GetInfo(className, name);
			accessorFields.put(accessor, info);
			return info;
		}
		private GetInfo createFieldRefConstant(IOField<?, ?> ioField) throws MalformedJorth{
			var name = "fieldRef_" + (constantIndex++) + "_" + ioField.getName().replaceAll("[^A-Za-z]", "");
			
			cw.field(GenericType.of(IOField.class).withArgs(cw.getTypeDef("ObjType")), name)
			  .visibility(Visibility.PRIVATE)
			  .staticFinal(code -> PipeCodeGen.loadConstant(code, constants, ioField, IOField.class));
			
			ClassName className = cw.getTypeDef("ThisClass");
			GetInfo   info      = new GetInfo(className, name);
			fieldRefFields.put(ioField, info);
			return info;
		}
		private GetInfo createPipeRefConstant(StructPipe<?> pipe) throws MalformedJorth{
			var struct = pipe.getType();
			var name   = "pipeRef_" + (constantIndex++) + "_" + struct.cleanName().replaceAll("[^A-Za-z]", "");
			
			cw.field(GenericType.of(StructPipe.class).withArgs(struct.getType()), name)
			  .visibility(Visibility.PRIVATE)
			  .staticFinal(code -> PipeCodeGen.loadConstant(code, constants, pipe, StructPipe.class));
			
			ClassName className = cw.getTypeDef("ThisClass");
			GetInfo   info      = new GetInfo(className, name);
			pipeRefs.put(pipe, info);
			return info;
		}
		
		public <E extends Enum<E>> void getEnumArray(Class<E> type, CodeBlock code) throws MalformedJorth{
			var info = enumArrays.get(type);
			if(info == null){
				info = createEnumConstant(type);
			}
			info.get(code);
		}
		
		public void getConstant(Object value, Class<?> type, CodeBlock block) throws MalformedJorth{
			var info = constantRefs.get(value);
			if(info == null){
				var name = "constant_" + constantIndex++;
				cw.field(type, name).visibility(Visibility.PRIVATE)
				  .staticFinal(code -> PipeCodeGen.loadConstant(code, constants, value, type));
				info = new GetInfo(cw.getTypeDef("ThisClass"), name);
				constantRefs.put(value, info);
			}
			info.get(block);
			block.cast(type);
		}
		
		public void getFieldRef(IOField<?, ?> field, CodeBlock block) throws MalformedJorth{
			var info = fieldRefFields.get(field);
			if(info == null){
				info = createFieldRefConstant(field);
			}
			info.get(block);
		}
		
		public void getValue(IOField<?, ?> field, CodeBlock block) throws MalformedJorth{
			getFieldRef(field, block);
			block.call("get", args -> {
				args.get("ioPool");
				getObject(args);
			}).cast(field.getType());
		}
		
		public void readStruct(StructPipe<?> pipe, CodeBlock block, CodeArg genericContext) throws MalformedJorth{
			var struct = pipe.getType();
			var info   = pipeRefs.get(pipe);
			if(info == null){
				info = createPipeRefConstant(pipe);
			}
			info.get(block);
			block.call("readNew", args -> {
				     args.get("provider").get("src");
				     genericContext.accept(args);
			     })
			     .cast(struct.getType());
		}
		
		public void get(IOField<?, ?> field, CodeBlock body) throws MalformedJorth{
			get(field.getAccessor(), body);
		}
		public void get(FieldAccessor<?> field, CodeBlock body) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(constructorFields){
						var localFieldName = localFields.get(field);
						Objects.requireNonNull(localFieldName);
						body.get(localFieldName);
						return;
					}
					
					switch(fom.getter()){
						case FieldAccessor.FieldOrMethod.AccessType.Field(var declaringClass, var name) -> {
							body.get(objectLocal)
							    .get(declaringClass, name);
						}
						case FieldAccessor.FieldOrMethod.AccessType.Method(var name) -> {
							body.get(objectLocal).call(name);
						}
					}
				}
				case VirtualAccessor<?> virutal -> {
					var name = localFields.get(field);
					if(name != null){
						body.get(name);
					}else if(existingValues){
						var accessor = getOrCreateAccessorInfo(field);
						body.call(PipeCodeGen.class, "getAccessorValue", args -> {
							accessor.get(args);
							args.get("ioPool").get(objectLocal);
						});
						if(field.getType().isPrimitive()){
							body.cast(SupportedPrimitive.get(field.getType()).orElseThrow().wrapper).unbox();
						}else body.cast(field.getType());
					}else{
						throw new MalformedJorth("Local field " + field.getName() + " does not exist");
					}
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		
		public String temporaryLocalField(Class<?> type, String nameHint, CodeBlock code) throws MalformedJorth{
			var name = "tmp_" + (nameHint != null? nameHint : type.getSimpleName().replaceAll("[^A-Za-z]", "")) + "_" + uniqueCounter();
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
	
	default void injectWriteField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
		accessMap.getFieldRef((IOField<?, ?>)this, body);
		body.call("write", args -> {
			args.get("ioPool").get("provider").get("dest");
			accessMap.getObject(args);
		});
	}
	
	default void injectSkipField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
		accessMap.getFieldRef((IOField<?, ?>)this, body);
		body.call("skip", args -> {
			args.get("ioPool").get("provider").get("src");
			accessMap.getObject(args);
			args.get("genericContext");
		});
	}
}
