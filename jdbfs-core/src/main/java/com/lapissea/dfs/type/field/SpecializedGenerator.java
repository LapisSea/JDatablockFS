package com.lapissea.dfs.type.field;

import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.utils.CodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.redo.CodeArg;
import com.lapissea.jorth.redo.CodeBlock;
import com.lapissea.util.NotImplementedException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface SpecializedGenerator{
	
	interface OnBitSpace<T extends IOInstance<T>> extends SpecializedGenerator{
		void injectReadFieldFromBits(CodeStream writer, CodeBlock body, AccessMap accessMap, String bitsFieldName) throws MalformedJorth, AccessMap.ConstantNeeded, UnsupportedCodeGenType;
		
		SizeDescriptor<T> getSizeDescriptor();
		FieldAccessor<T> getAccessor();
		
		@Override
		default void injectReadField(CodeStream writer, CodeBlock body, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded, UnsupportedCodeGenType{
			var bits  = Math.toIntExact(getSizeDescriptor().requireFixed(WordSpace.BIT));
			var bytes = BitUtils.bitsToBytes(bits);
			var name  = accessMap.temporaryLocalField(bits<32? int.class : long.class, writer, body);
			
			CodeUtils.readBytesFromSrc(writer, body, bytes);
			CodeUtils.rawBitsToValidatedBits(writer, body, bytes, bits);
			if(bits<32){
				writer.write("cast int");
				body.cast(int.class);
			}
			writer.write("set #field {}", name);
			body.set(name);
			
			injectReadFieldFromBits(writer, body, accessMap, name);
			
		}
	}
	
	final class AccessMap{
		
		public sealed interface ConstantRequest{
			record FieldAcc(FieldAccessor<?> val) implements AccessMap.ConstantRequest{ }
			
			record EnumArr(Class<? extends Enum<?>> type) implements AccessMap.ConstantRequest{ }
			
			record FieldRef(IOField<?, ?> type) implements AccessMap.ConstantRequest{ }
			
			record DebugField(Class<?> type, String name, String initCode) implements AccessMap.ConstantRequest{ }
		}
		
		public static final class ConstantNeeded extends Exception{
			public final List<AccessMap.ConstantRequest> constants;
			public ConstantNeeded(AccessMap.ConstantRequest constant)   { this(List.of(constant)); }
			public ConstantNeeded(Collection<ConstantRequest> constants){ this.constants = List.copyOf(constants); }
		}
		
		private record GetInfo(ClassName className, String fieldName){ }
		
		private final Map<FieldAccessor<?>, String>            localFields    = new HashMap<>();
		private final Map<FieldAccessor<?>, String>            localFields2   = new HashMap<>();
		private final Map<FieldAccessor<?>, AccessMap.GetInfo> accessorFields = new HashMap<>();
		private final Map<IOField<?, ?>, AccessMap.GetInfo>    fieldRefFields = new HashMap<>();
		private final Map<Class<?>, AccessMap.GetInfo>         enumArrays     = new HashMap<>();
		
		private int tmpFieldCount = 0;
		
		private final List<Set<String>> temporaryStack = new ArrayList<>();
		
		/// indicates that if any field requires the (var) io pool
		private boolean hasIOPool;
		/// indicates that the object being built has its fields in local variables, not a builder
		private boolean localObject;
		
		public void setup(boolean hasIOPool, boolean localObject){
			this.hasIOPool = hasIOPool;
			this.localObject = localObject;
			tmpFieldCount = 0;
			localFields.clear();
			localFields2.clear();
			temporaryStack.clear();
		}
		
		public void set(FieldAccessor<?> field, CodeBlock body, CodeArg args) throws MalformedJorth, AccessMap.ConstantNeeded{
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
						
						if(!localFields2.containsKey(field)){
							var name = "initVal_" + field.getName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
							localFields2.put(field, name);
							body.var(field.getType(), name);
						}
						var localFieldName = localFields2.get(field);
						body.set(localFieldName);
					}
				}
				case VirtualAccessor<?> virutal -> {
					args.accept(body);
					
					if(!localFields2.containsKey(field)){
						var name = "virt_" + field.getName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
						localFields2.put(field, name);
						body.var(field.getType(), name);
					}
					
					var localFieldName = localFields2.get(field);
					body.set(localFieldName);
					
					if(hasIOPool){
						var accessorInfo = accessorFields.get(field);
						if(accessorInfo == null){
							throw new AccessMap.ConstantNeeded(new AccessMap.ConstantRequest.FieldAcc(field));
						}
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
		public void preSet(FieldAccessor<?> field, CodeStream writer) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(localObject){
						return;
					}
					writer.write("dup");
					switch(fom.setter()){
						case FieldAccessor.FieldOrMethod.AccessType.Field ignore -> { }
						case FieldAccessor.FieldOrMethod.AccessType.Method(var name) -> {
							writer.write("call {!} start", name);
						}
					}
				}
				case VirtualAccessor<?> virutal -> { }
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		public void set(FieldAccessor<?> field, CodeStream writer) throws MalformedJorth, AccessMap.ConstantNeeded{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(localObject){
						if(!localFields.containsKey(field)){
							var name = "initVal_" + field.getName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
							localFields.put(field, name);
							writer.write("field {} {}", name, field.getType());
						}
						var localFieldName = localFields.get(field);
						writer.write("set #field {}", localFieldName);
					}else{
						switch(fom.setter()){
							case FieldAccessor.FieldOrMethod.AccessType.Field(var declaringClass, var name) -> {
								writer.write("set {} {!}", declaringClass, name);
							}
							case FieldAccessor.FieldOrMethod.AccessType.Method(var name) -> {
								writer.write("end");
								
							}
						}
					}
				}
				case VirtualAccessor<?> virutal -> {
					if(!localFields.containsKey(field)){
						var name = "virt_" + field.getName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
						localFields.put(field, name);
						writer.write("field {} {}", name, field.getType());
					}
					
					var localFieldName = localFields.get(field);
					writer.write("set #field {}", localFieldName);
					if(hasIOPool){
						var accessorInfo = accessorFields.get(field);
						if(accessorInfo == null){
							throw new AccessMap.ConstantNeeded(new AccessMap.ConstantRequest.FieldAcc(field));
						}
						String fnName;
						if(field.getType() == long.class) fnName = "setLong";
						else if(field.getType() == int.class) fnName = "setInt";
						else if(field.getType() == boolean.class) fnName = "setBoolean";
						else if(field.getType() == byte.class) fnName = "setByte";
						else fnName = "set";
						
						writer.write("""
							             get #arg ioPool
							             call {} start
							              get {!} {!}
							              get #field {}
							             end
							             """, fnName, accessorInfo.className, accessorInfo.fieldName, localFieldName);
					}
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		public <E extends Enum<E>> void getEnumArray(Class<E> type, CodeStream writer) throws MalformedJorth, AccessMap.ConstantNeeded{
			var info = enumArrays.get(type);
			if(info == null){
				throw new AccessMap.ConstantNeeded(new AccessMap.ConstantRequest.EnumArr(type));
			}
			writer.write("get {} {}", info.className, info.fieldName);
		}
		public <E extends Enum<E>> void getEnumArray(Class<E> type, CodeBlock code) throws MalformedJorth{
			var info = enumArrays.get(type);
			if(info == null){
				throw new NotImplementedException("GENERATE ENUM ON THE FLY");
			}
			code.get(info.className, info.fieldName);
		}
		public <E extends Enum<E>> void getFieldRef(IOField<?, ?> field, CodeStream writer) throws MalformedJorth, AccessMap.ConstantNeeded{
			var info = fieldRefFields.get(field);
			if(info == null){
				throw new AccessMap.ConstantNeeded(new AccessMap.ConstantRequest.FieldRef(field));
			}
			writer.write("get {} {}", info.className, info.fieldName);
		}
		public <E extends Enum<E>> void getFieldRef(IOField<?, ?> field, CodeBlock block) throws MalformedJorth{
			var info = fieldRefFields.get(field);
			if(info == null){
				throw new NotImplementedException("GENERATE ENUM ON THE FLY");
			}
			block.get(info.className, info.fieldName);
		}
		
		public void get(IOField<?, ?> field, CodeStream writer) throws MalformedJorth{
			get(field.getAccessor(), writer);
		}
		public void get(FieldAccessor<?> field, CodeStream writer) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(localObject){
						var localFieldName = localFields.get(field);
						Objects.requireNonNull(localFieldName);
						writer.write("get #field {}", localFieldName);
						return;
					}
					
					switch(fom.setter()){
						case FieldAccessor.FieldOrMethod.AccessType.Field(var declaringClass, var name) -> {
							writer.write("dup");
							writer.write("get {} {!}", declaringClass, name);
						}
						case FieldAccessor.FieldOrMethod.AccessType.Method(var name) -> {
							throw new NotImplementedException("call method");
						}
					}
				}
				case VirtualAccessor<?> virutal -> {
					var name = localFields.get(field);
					if(name == null) throw new MalformedJorth("Local field " + field.getName() + " does not exist");
					writer.write("get #field {}", name);
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		public void get(IOField<?, ?> field, CodeBlock body) throws MalformedJorth{
			get(field.getAccessor(), body);
		}
		public void get(FieldAccessor<?> field, CodeBlock body) throws MalformedJorth{
			switch(field){
				case FieldAccessor.FieldOrMethod fom -> {
					if(localObject){
						var localFieldName = localFields2.get(field);
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
					var name = localFields2.get(field);
					if(name == null) throw new MalformedJorth("Local field " + field.getName() + " does not exist");
					body.get(name);
				}
				default -> throw new UnsupportedOperationException(field.getClass().getTypeName() + " not supported");
			}
		}
		public void addAccessorField(FieldAccessor<?> accessor, ClassName className, String fieldName){
			accessorFields.put(accessor, new AccessMap.GetInfo(className, fieldName));
		}
		public void addFieldRefField(IOField<?, ?> accessor, ClassName className, String fieldName){
			fieldRefFields.put(accessor, new AccessMap.GetInfo(className, fieldName));
		}
		public void addEnumArray(Class<?> type, ClassName className, String fieldName){
			enumArrays.put(type, new AccessMap.GetInfo(className, fieldName));
		}
		
		public String temporaryLocalField(Class<?> type, CodeStream writer, CodeBlock code) throws MalformedJorth{
			var name = "tmp_" + type.getSimpleName().replaceAll("[^A-Za-z]", "") + "_" + uniqueCounter();
			writer.write("field {} {}", name, type);
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
		public void dropTemporary(CodeStream writer, CodeBlock body) throws MalformedJorth{
			for(String field : temporaryStack.removeLast()){
				writer.write("forget #field " + field);
				body.forgetVar(field);
			}
		}
		
		private int uniqueCounter(){
			return localFields.size() + tmpFieldCount;
		}
	}
	
	void injectReadField(CodeStream writer, CodeBlock body, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded, UnsupportedCodeGenType;
}
