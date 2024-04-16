package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.CollectionInfoAnalysis;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.WrapperStructs;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.List;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract class DynamicSupport{
	@SuppressWarnings({"unchecked"})
	public static long calcSize(DataProvider prov, Object val){
		return switch(val){
			case null -> 0;
			case String str -> AutoText.PIPE.calcUnknownSize(prov, new AutoText(str), WordSpace.BYTE);
			case IOInstance inst -> {
				if(inst instanceof IOInstance.Unmanaged<?> u){
					yield ChunkPointer.DYN_SIZE_DESCRIPTOR.calcUnknown(null, prov, u.getPointer(), WordSpace.BYTE);
				}else{
					yield StandardStructPipe.sizeOfUnknown(prov, inst, WordSpace.BYTE);
				}
			}
			case Enum<?> e -> BitUtils.bitsToBytes(EnumUniverse.of(e.getClass()).bitSize);
			default -> {
				var primitiveO = SupportedPrimitive.get(val.getClass());
				if(primitiveO.isPresent()){
					var psiz = primitiveO.get();
					yield switch(psiz){
						case LONG -> 1 + NumberSize.bySizeSigned((long)val).bytes;
						case INT -> 1 + NumberSize.bySizeSigned((int)val).bytes;
						case BOOLEAN, BYTE, DOUBLE, FLOAT, SHORT, CHAR -> psiz.maxSize.get();
					};
				}
				
				var res = CollectionInfoAnalysis.analyze(val);
				if(res != null) yield DynamicCollectionSupport.calcCollectionSize(prov, val, res);
				
				var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(val.getClass());
				if(wrapper != null){
					var obj = wrapper.constructor().apply(val);
					yield StandardStructPipe.sizeOfUnknown(prov, obj, WordSpace.BYTE);
				}
				
				throw new NotImplementedException(val.getClass() + "");
			}
		};
	}
	
	@SuppressWarnings("unchecked")
	public static void writeValue(DataProvider provider, ContentWriter dest, Object val) throws IOException{
		switch(val){
			case null -> { }
			case String str -> AutoText.STR_PIPE.write(provider, dest, str);
			case IOInstance.Unmanaged inst -> ChunkPointer.DYN_PIPE.write(provider, dest, inst.getPointer());
			case IOInstance inst -> StandardStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			case Enum e -> FlagWriter.writeSingle(dest, EnumUniverse.of(e.getClass()), e);
			case Boolean v -> dest.writeBoolean(v);
			case Character v -> dest.writeChar2(v);
			case Float v -> NumberSize.INT.writeFloating(dest, v);
			case Double v -> NumberSize.LONG.writeFloating(dest, v);
			case Byte v -> dest.writeInt1(v);
			case Short v -> dest.writeInt2(v);
			case Number integer -> {
				if(DEBUG_VALIDATION){
					ensureInt(integer.getClass());
				}
				long value = integer.longValue();
				var  num   = NumberSize.bySizeSigned(value);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
				num.writeSigned(dest, value);
			}
			default -> {
				var res = CollectionInfoAnalysis.analyze(val);
				if(res != null){
					DynamicCollectionSupport.writeCollection(provider, dest, val, res);
					break;
				}
				
				var type    = val.getClass();
				var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(type);
				if(wrapper != null){
					var obj = wrapper.constructor().apply(val);
					var pip = StandardStructPipe.of(wrapper.struct());
					pip.write(provider, dest, obj);
					break;
				}
				
				throw new NotImplementedException(val.getClass() + "");
			}
		}
	}
	
	private static void ensureInt(Class<?> tyo){
		if(!List.<Class<? extends Number>>of(Byte.class, Short.class, Integer.class, Long.class).contains(tyo))
			throw new AssertionError(tyo + " is not an integer");
	}
	
	public static Object readTyp(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var db  = provider.getTypeDb();
		var typ = typDef.getTypeClass(db);
		if(typ == Boolean.class) return src.readBoolean();
		if(typ == Character.class) return src.readChar2();
		if(UtilL.instanceOf(typ, Number.class)){
			if(typ == Float.class) return (float)NumberSize.INT.readFloating(src);
			if(typ == Double.class) return NumberSize.LONG.readFloating(src);
			if(typ == Byte.class) return src.readInt1();
			if(typ == Short.class) return src.readInt2();
			
			ensureInt(typ);
			var num     = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
			var longNum = num.readSigned(src);
			if(typ == Integer.class) return (int)longNum;
			if(typ == Long.class) return longNum;
			throw new NotImplementedException("Unkown integer type" + typ);
		}
		if(typ == String.class) return AutoText.STR_PIPE.readNew(provider, src, null);
		
		if(IOInstance.isUnmanaged(typ)){
			var uStruct = Struct.Unmanaged.ofUnknown(typ);
			var ptr     = ChunkPointer.DYN_PIPE.readNew(provider, src, null);
			var ch      = ptr.dereference(provider);
			return uStruct.make(provider, ch, typDef);
		}
		if(IOInstance.isInstance(typ)){
			var struct = Struct.ofUnknown(typ);
			var pipe   = StandardStructPipe.of(struct);
			return pipe.readNew(provider, src, genericContext);
		}
		if(typ.isEnum()){
			var universe = EnumUniverse.ofUnknown(typ);
			return FlagReader.readSingle(src, universe);
		}
		
		if(CollectionInfoAnalysis.isTypeCollection(typ)){
			return DynamicCollectionSupport.readCollection(typDef, provider, src, genericContext);
		}
		
		var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(typ);
		if(wrapper != null){
			var pip = StandardStructPipe.of(wrapper.struct());
			var obj = pip.readNew(provider, src, genericContext);
			return obj.get();
		}
		
		throw new NotImplementedException(typ + "");
	}
	
	public static void skipTyp(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var        typ = typDef.getTypeClass(provider.getTypeDb());
		NumberSize siz = null;
		if(typ == Boolean.class) siz = NumberSize.BYTE;
		else if(typ == Float.class) siz = NumberSize.INT;
		else if(typ == Double.class) siz = NumberSize.LONG;
		else if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
		}
		if(siz != null){
			src.skipExact(siz);
			return;
		}
		
		if(typ == String.class){
			AutoText.PIPE.skip(provider, src, genericContext);
			return;
		}
		if(IOInstance.isInstance(typ)){
			if(IOInstance.isUnmanaged(typ)){
				ChunkPointer.DYN_PIPE.skip(provider, src, null);
			}else{
				var pipe = StandardStructPipe.of(Struct.ofUnknown(typ));
				if(!src.optionallySkipExact(pipe.getSizeDescriptor().getFixed(WordSpace.BYTE))){
					pipe.skip(provider, src, genericContext);
				}
			}
			return;
		}
		
		if(typ.isEnum()){
			var universe = EnumUniverse.ofUnknown(typ);
			universe.numSize(false).skip(src);
			return;
		}
		
		if(typ.isArray() || UtilL.instanceOf(typ, List.class)){
			readTyp(typDef, provider, src, genericContext);//TODO just skip, don't read
		}
		
		var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(typ);
		if(wrapper != null){
			var pip = StandardStructPipe.of(wrapper.struct());
			pip.skip(provider, src, genericContext);
			return;
		}
		
		throw new NotImplementedException(typ + "");
	}
}
