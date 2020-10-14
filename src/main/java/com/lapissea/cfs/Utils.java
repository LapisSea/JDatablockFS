package com.lapissea.cfs;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
		int    read;
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
			
			//noinspection unchecked
			return (T)site.getTarget().invoke();
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda\n"+method+"\n"+functionalInterface, e);
		}
	}
	
	public static <T> T findConstructorInstance(Class<T> rwClass, List<Map.Entry<Supplier<Stream<Class<?>>>, Supplier<Stream<Object>>>> definition){
		return definition.stream()
		                 .map(e->{
			                 try{
				                 return rwClass.getConstructor(e.getKey().get().toArray(Class[]::new))
				                               .newInstance(e.getValue().get().toArray(Object[]::new));
			                 }catch(ReflectiveOperationException ignored){
				                 return null;
			                 }
		                 })
		                 .filter(Objects::nonNull)
		                 .findFirst()
		                 .orElseThrow(()->new RuntimeException("cannot construct "+rwClass.getName()));
	}
	
	public static <T> Constructor<T> tryMapConstructor(Class<T> rwClass, List<Supplier<Stream<Class<?>>>> definition) throws ReflectiveOperationException{
		return tryMapConstructor(rwClass, definition, (i, e)->e);
	}
	
	public static <T, L> L tryMapConstructor(Class<T> rwClass, List<Supplier<Stream<Class<?>>>> definition, BiFunction<Integer, Constructor<T>, L> mapper) throws ReflectiveOperationException{
		return IntStream.range(0, definition.size())
		                .mapToObj(e->{
			                try{
				                return Map.entry(e, rwClass.getConstructor(definition.get(e).get().toArray(Class[]::new)));
			                }catch(ReflectiveOperationException ignored){
				                return null;
			                }
		                })
		                .filter(Objects::nonNull)
		                .map(e->mapper.apply(e.getKey(), e.getValue()))
		                .findFirst()
		                .orElseThrow(()->new ReflectiveOperationException("cannot find constructor "+rwClass.getName()));
		
	}
}
