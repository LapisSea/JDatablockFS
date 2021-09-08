package com.lapissea.cfs;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.util.UtilL.*;

public class Utils{
	
	public interface ConsumerBaII{
		void accept(byte[] bytes, int off, int len) throws IOException;
	}
	
	public static void zeroFill(OutputStream dest, long size) throws IOException{
		zeroFill(dest::write, size);
	}
	
	public static void transferExact(ContentReader src, ContentWriter dest, long amount) throws IOException{
		if(amount<0) throw new IllegalArgumentException(amount+" can't be negative");
		byte[] buffer   =new byte[(int)Math.min(amount, 1<<13)];
		long   remaining=amount;
		while(remaining>0){
			int toTransfer=(int)Math.min(remaining, buffer.length);
			src.readFully(buffer, 0, toTransfer);
			dest.write(buffer, 0, toTransfer);
			remaining-=toTransfer;
		}
	}
	
	public static void zeroFill(ConsumerBaII dest, long size) throws IOException{
		
		var  part=new byte[(int)Math.min(size, 1024)];
		long left=size;
		while(left>0){
			int write=(int)Math.min(left, part.length);
			dest.accept(part, 0, write);
			left-=write;
		}
	}
	
	public static <K, V> boolean isCacheValid(Map<K, V> disk, Map<K, V> cache){
		for(var entry : cache.entrySet()){
			var key=entry.getKey();
			var val=entry.getValue();
			
			var diskVal=disk.get(key);
			if(!val.equals(diskVal)){
				return false;
			}
		}
		return true;
	}
	
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
	
	public static <FInter, T extends FInter> T makeLambda(Method method, Class<FInter> functionalInterface){
		try{
			var lookup=MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
			method.setAccessible(true);
			var handle=lookup.unreflect(method);
			
			Method functionalInterfaceFunction=Arrays.stream(functionalInterface.getMethods()).filter(m->!Modifier.isStatic(m.getModifiers())).findAny().orElseThrow();
			
			MethodType signature=MethodType.methodType(functionalInterfaceFunction.getReturnType(), functionalInterfaceFunction.getParameterTypes());
			
			CallSite site=LambdaMetafactory.metafactory(lookup,
			                                            functionalInterfaceFunction.getName(),
			                                            MethodType.methodType(functionalInterface),
			                                            signature,
			                                            handle,
			                                            handle.type());
			
			return (T)site.getTarget().invoke();
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda\n"+method+"\n"+functionalInterface, e);
		}
	}
	
	public static <Arr> IntFunction<Arr[]> newArray(Class<Arr[]> type){
		if(!type.isArray()) throw new IllegalArgumentException();
		var elType=type.componentType();
		return i->(Arr[])Array.newInstance(elType, i);
	}
	
	public static <FInter> Method getFunctionalMethod(Class<FInter> functionalInterface){
		var methods=Arrays.stream(functionalInterface.getMethods()).filter(m->!Modifier.isStatic(m.getModifiers())&&Modifier.isAbstract(m.getModifiers())).toList();
		if(methods.size()!=1){
			throw new IllegalArgumentException(functionalInterface+" is not a functional interface!");
		}
		return methods.get(0);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface){
		try{
			var lookup=MethodHandles.privateLookupIn(constructor.getDeclaringClass(), MethodHandles.lookup());
			constructor.setAccessible(true);
			var handle=lookup.unreflectConstructor(constructor);
			
			Method functionalInterfaceFunction=getFunctionalMethod(functionalInterface);
			
			MethodType signature=MethodType.methodType(functionalInterfaceFunction.getReturnType(), functionalInterfaceFunction.getParameterTypes());
			
			CallSite site=LambdaMetafactory.metafactory(lookup,
			                                            functionalInterfaceFunction.getName(),
			                                            MethodType.methodType(functionalInterface),
			                                            signature,
			                                            handle,
			                                            handle.type());
			
			return Objects.requireNonNull((T)site.getTarget().invoke());
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda\n"+constructor+"\n"+functionalInterface, e);
		}
	}
	
	public static VarHandle makeVarHandle(Field field){
		try{
			var lookup=MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
			field.setAccessible(true);
			return lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
		}catch(Throwable e){
			throw new RuntimeException("failed to create VarHandle\n"+field, e);
		}
	}
	
	public static MethodHandle makeMethodHandle(@NotNull Method method){
		try{
			var lookup=MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
			method.setAccessible(true);
			return lookup.unreflect(method);
		}catch(Throwable e){
			throw new RuntimeException("failed to create MethodHandle\n"+method, e);
		}
	}
	
	@NotNull
	public static <FInter, T extends FInter> T findConstructor(@NotNull Class<?> clazz, Class<FInter> functionalInterface, Class<?>... parameterTypes){
		try{
			Constructor<?> lconst;
			if(Modifier.isPrivate(clazz.getModifiers())){
				lconst=clazz.getDeclaredConstructor(parameterTypes);
			}else{
				lconst=clazz.getConstructor(parameterTypes);
			}
			
			return Utils.makeLambda(lconst, functionalInterface);
		}catch(ReflectiveOperationException ce){
			
			
			
			try{
				Method of=clazz.getMethod("of", parameterTypes);
				if(!Modifier.isStatic(of.getModifiers())) throw new ReflectiveOperationException(of+" not static");
				if(!Modifier.isPublic(of.getModifiers())) throw new ReflectiveOperationException(of+" not public");
				if(!of.getReturnType().equals(clazz)) throw new ReflectiveOperationException(of+" does not return "+clazz);
				
				return Utils.makeLambda(of, functionalInterface);
			}catch(ReflectiveOperationException ofe){
				throw new MalformedStructLayout(clazz.getName()+" does not have a valid constructor or of static method with arguments of "+Arrays.toString(parameterTypes));
			}
		}
	}
	
	public static int floatToShortBits(float fval){
		int fbits=Float.floatToIntBits(fval);
		int sign =fbits >>> 16&0x8000;
		int val  =(fbits&0x7fffffff)+0x1000;
		
		if(val>=0x47800000){
			if((fbits&0x7fffffff)>=0x47800000){
				if(val<0x7f800000) return sign|0x7c00;
				return sign|0x7c00|(fbits&0x007fffff) >>> 13;
			}
			return sign|0x7bff;
		}
		if(val>=0x38800000) return sign|val-0x38000000 >>> 13;
		if(val<0x33000000) return sign;
		val=(fbits&0x7fffffff) >>> 23;
		return sign|((fbits&0x7fffff|0x800000)+(0x800000 >>> val-102) >>> 126-val);
	}
	
	public static float shortBitsToFloat(int hbits){
		int mant=hbits&0x03ff;
		int exp =hbits&0x7c00;
		if(exp==0x7c00) exp=0x3fc00;
		else if(exp!=0){
			exp+=0x1c000;
			if(mant==0&&exp>0x1c400) return Float.intBitsToFloat((hbits&0x8000)<<16|exp<<13|0x3ff);
		}else if(mant!=0){
			exp=0x1c400;
			do{
				mant<<=1;
				exp-=0x400;
			}while((mant&0x400)==0);
			mant&=0x3ff;
		}
		return Float.intBitsToFloat((hbits&0x8000)<<16|(exp|mant)<<13);
	}
	
	public static void requireNull(Object o){
		if(o!=null) throw new IllegalStateException();
	}
	
	public static int bitToByte(int bits){
		return (int)Math.ceil(bits/(double)Byte.SIZE);
	}
	public static long bitToByte(long bits){
		if(bits<=Byte.SIZE) return 1;
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
		
		if(type instanceof ParameterizedType parm){
			Type[] args=parm.getActualTypeArguments(), testArgs;
			if(testType instanceof ParameterizedType parm2) testArgs=parm2.getActualTypeArguments();
			else return false;
			if(args.length!=testArgs.length) return false;
			for(int i=0;i<args.length;i++){
				if(!genericInstanceOf(testArgs[i], args[i])) return false;
			}
			return true;
		}
		
		return UtilL.instanceOf((Class<?>)testType, (Class<?>)type);
	}
}
