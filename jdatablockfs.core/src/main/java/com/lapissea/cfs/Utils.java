package com.lapissea.cfs;

import com.lapissea.cfs.internal.MyUnsafe;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.Assert;

@SuppressWarnings({"unchecked", "unused"})
public class Utils{
	
	public static void fairDistribute(long[] values, long toDistribute){
		
		long totalUsage=Arrays.stream(values).sum();
		
		var free=toDistribute-totalUsage;
		
		if(free>0){
			int toUse=values.length;
			do{
				var bulkAdd=free/toUse;
				
				for(int i=0;i<toUse;i++){
					values[i]+=bulkAdd;
					free-=bulkAdd;
				}
				toUse--;
			}while(free>0);
		}else{
			Assert(free==0);
		}
	}
	
	public static int bitToByte(int bits){
		return (int)Math.ceil(bits/(double)Byte.SIZE);
	}
	public static long bitToByte(long bits){
		return (long)Math.ceil(bits/(double)Byte.SIZE);
	}
	public static OptionalLong bitToByte(OptionalLong bits){
		return bits.isPresent()?OptionalLong.of(bitToByte(bits.getAsLong())):bits;
	}
	
	public static OptionalLong addIfBoth(OptionalLong a, OptionalLong b){
		if(a.isEmpty()) return a;
		if(b.isEmpty()) return b;
		return OptionalLong.of(a.getAsLong()+b.getAsLong());
	}
	
	public static OptionalLong maxIfBoth(OptionalLong a, OptionalLong b){
		if(a.isEmpty()) return a;
		if(b.isEmpty()) return b;
		return OptionalLong.of(Math.max(a.getAsLong(), b.getAsLong()));
	}
	
	public static OptionalLong minIfBoth(OptionalLong a, OptionalLong b){
		if(a.isEmpty()) return a;
		if(b.isEmpty()) return b;
		return OptionalLong.of(Math.min(a.getAsLong(), b.getAsLong()));
	}
	
	public static String byteArrayToBitString(byte[] data){
		return byteArrayToBitString(data, 0, data.length);
	}
	public static String byteArrayToBitString(byte[] data, int length){
		return byteArrayToBitString(data, 0, length);
	}
	public static String byteArrayToBitString(byte[] data, int offset, int length){
		return IntStream.range(offset, offset+length)
		                .map(i->data[i]&0xFF)
		                .mapToObj(b->String.format("%8s", Integer.toBinaryString(b)).replace(' ', '0'))
		                .map(s->new StringBuilder(s).reverse())
		                .collect(Collectors.joining());
	}
	
	@Deprecated
	public static Class<?> typeToRaw(Class<?> type){
		return type;
	}
	public static Class<?> typeToRaw(Type type){
		if(type instanceof Class<?> c) return c;
		if(type instanceof ParameterizedType c) return (Class<?>)c.getRawType();
		if(type instanceof TypeVariable<?> c){
			return typeToRaw(extractFromVarType(c));
		}
		throw new IllegalArgumentException(type.toString());
	}
	
	public static Type prottectFromVarType(Type type){
		if(type instanceof TypeVariable<?> c){
			return extractFromVarType(c);
		}
		return type;
	}
	
	public static Type extractFromVarType(TypeVariable<?> c){
		var bounds=c.getBounds();
		if(bounds.length==1){
			var typ=bounds[0];
			return typ;
		}
		throw new NotImplementedException(TextUtil.toString("wut? ", bounds));
	}
	
	public static boolean genericInstanceOf(Type testType, Type type){
		if(testType.equals(type)) return true;
		
		if(testType instanceof TypeVariable<?> c) return genericInstanceOf(extractFromVarType(c), type);
		if(type instanceof TypeVariable<?> c) return genericInstanceOf(testType, extractFromVarType(c));
		
		if(type instanceof Class||testType instanceof Class<?>){
			var rawTestType=typeToRaw(testType);
			var rawType    =typeToRaw(type);
			return UtilL.instanceOf(rawTestType, rawType);
		}
		
		var pTestType=(ParameterizedType)testType;
		var pType    =(ParameterizedType)type;
		
		var rawTestType=(Class<?>)pTestType.getRawType();
		var rawType    =(Class<?>)pType.getRawType();
		
		var rawCast=UtilL.instanceOf(rawTestType, rawType);
		if(!rawCast) return false;
		
		Type[] testArgs=pTestType.getActualTypeArguments();
		Type[] args    =pType.getActualTypeArguments();
		
		if(testArgs.length!=args.length){
			return false;
		}
		
		for(int i=0;i<testArgs.length;i++){
			if(!genericInstanceOf(testArgs[i], args[i])) return false;
		}
		
		return true;
	}
	
	public static <E, C extends Collection<E>> C nullIfEmpty(C collection){
		if(collection.isEmpty()) return null;
		return collection;
	}
	public static <K, V, C extends Map<K, V>> C nullIfEmpty(C map){
		if(map.isEmpty()) return null;
		return map;
	}
	
	
	private static final IOList.IOIterator.Iter<?> EMPTY_ITER=new IOList.IOIterator.Iter<>(){
		@Override
		public boolean hasNext(){
			return false;
		}
		@Override
		public Object ioNext(){
			throw new NoSuchElementException();
		}
	};
	
	public static <T> IOList.IOIterator.Iter<T> emptyIter(){
		return (IOList.IOIterator.Iter<T>)EMPTY_ITER;
	}
	
	public static String toShortString(Object o){
		if(o instanceof IOInstance<?> i) return i.toShortString();
		return TextUtil.toShortString(o);
	}
	
	private static final int BARR_OFF=MyUnsafe.UNSAFE.arrayBaseOffset(byte[].class);
	
	public static long read8(byte[] data, int off, int len){
		if(!MyUnsafe.IS_BIG_ENDIAN&&len==8){
			Objects.checkFromIndexSize(off, len, data.length);
			return Long.reverseBytes(MyUnsafe.UNSAFE.getLong(data, BARR_OFF+off));
		}
		
		final var lm1=len-1;
		long      val=0;
		for(int i=0;i<len;i++){
			val|=(data[off+i]&255L)<<((lm1-i)*8);
		}
		return val;
	}
	
	public static void write8(long v, byte[] writeBuffer, int off, int len){
		if(v==0){
			for(int i=off, j=off+len;i<j;i++){
				writeBuffer[i]=0;
			}
			return;
		}
		
		if(!MyUnsafe.IS_BIG_ENDIAN&&len==8){
			Objects.checkFromIndexSize(off, len, writeBuffer.length);
			MyUnsafe.UNSAFE.putLong(writeBuffer, BARR_OFF+off, Long.reverseBytes(v));
			return;
		}
		
		final var lm1=len-1;
		
		for(int i=0;i<len;i++){
			writeBuffer[off+i]=(byte)(v >>> ((lm1-i)*8));
		}
	}
	
	public static Optional<String> optionalProperty(String name){
		return Optional.ofNullable(System.getProperty(name));
	}
	
	public static void frameToStr(StringBuilder sb, StackWalker.StackFrame frame){
		frameToStr(sb, frame, true);
	}
	public static void frameToStr(StringBuilder sb, StackWalker.StackFrame frame, boolean addLine){
		classToStr(sb, frame.getDeclaringClass());
		sb.append('.').append(frame.getMethodName());
		if(addLine) sb.append('(').append(frame.getLineNumber()).append(')');
	}
	public static void classToStr(StringBuilder sb, Class<?> clazz){
		var enclosing=clazz.getEnclosingClass();
		if(enclosing!=null){
			classToStr(sb, enclosing);
			sb.append('.').append(clazz.getSimpleName());
			return;
		}
		
		var p=clazz.getPackageName();
		for(int i=0;i<p.length();i++){
			if(i==0){
				sb.append(p.charAt(i));
			}else if(p.charAt(i-1)=='.'){
				sb.append(p, i-1, i+1);
			}
		}
		sb.append('.').append(clazz.getSimpleName());
	}
	public static Class<?> getCallee(int depth){
		return getCallee(s->s.skip(depth+2));
	}
	public static Class<?> getCallee(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	public static StackWalker.StackFrame getFrame(int depth){
		return getFrame(s->s.skip(depth+2));
	}
	public static StackWalker.StackFrame getFrame(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow());
	}
	
	
	public static String classNameToHuman(String name, boolean doShort){
		int arrayLevels=0;
		for(int i=0;i<name.length();i++){
			if(name.charAt(i)=='[') arrayLevels++;
			else break;
		}
		name=name.substring(arrayLevels);
		if(name.startsWith("L")) name=name.substring(1, name.length()-1);
		
		
		if(doShort){
			var index=name.lastIndexOf('.');
			name=(index!=-1?name.substring(name.lastIndexOf('.')):name)+
			     ("[]".repeat(arrayLevels));
		}else{
			var parts=TextUtil.splitByChar(name, '.');
			if(parts.length==1) name=name+("[]".repeat(arrayLevels));
			else name=Arrays.stream(parts)
			                .limit(parts.length-1)
			                .map(c->c.charAt(0)+"")
			                .collect(Collectors.joining("."))+
			          "."+parts[parts.length-1]+
			          ("[]".repeat(arrayLevels));
		}
		return name;
	}
}
