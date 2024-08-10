package com.lapissea.dfs;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongBinaryOperator;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.Assert;

@SuppressWarnings({"unused"})
public final class Utils{
	
	public static void fairDistribute(long[] values, long toDistribute){
		
		long totalUsage = Iters.ofLongs(values).sum();
		
		var free = toDistribute - totalUsage;
		
		if(free>0){
			int toUse = values.length;
			do{
				var bulkAdd = free/toUse;
				
				for(int i = 0; i<toUse; i++){
					values[i] += bulkAdd;
					free -= bulkAdd;
				}
				toUse--;
			}while(free>0);
		}else{
			Assert(free == 0);
		}
	}
	
	@Deprecated
	public static Class<?> typeToRaw(Class<?> type){
		return type;
	}
	public static Class<?> typeToRaw(Type type){
		return switch(type){
			case Class<?> cl -> cl;
			case ParameterizedType parm -> typeToRaw(parm.getRawType());
			case TypeVariable<?> var -> typeToRaw(extractBound(var));
			case WildcardType wild -> typeToRaw(extractBound(wild));
			case GenericArrayType a -> typeToRaw(a.getGenericComponentType());
			default -> throw new NotImplementedException(type.getClass().getName());
		};
	}
	
	public static Type extractBound(TypeVariable<?> c){
		return extractBound(c.getBounds());
	}
	public static Type extractBound(WildcardType type){
		return extractBound(type.getLowerBounds(), type.getUpperBounds());
	}
	
	public static Type extractBound(Type[] lower, Type[] upper){
		if(lower.length == 0){
			if(upper.length == 0 || Object.class == upper[0]) return Object.class;
			else return extractBound(upper);
		}else return extractBound(lower);
	}
	public static Type extractBound(Type[] bounds){
		if(bounds.length == 1){
			return bounds[0];
		}
		throw new NotImplementedException("Multiple bounds not implemented yet");//TODO
	}
	
	public static boolean genericInstanceOf(Type testType, Type type){
		if(testType.equals(type)) return true;
		
		if(testType instanceof TypeVariable<?> c) return genericInstanceOf(extractBound(c), type);
		if(type instanceof TypeVariable<?> c) return genericInstanceOf(testType, extractBound(c));
		
		if(type instanceof Class || testType instanceof Class<?>){
			var rawTestType = typeToRaw(testType);
			var rawType     = typeToRaw(type);
			return UtilL.instanceOf(rawTestType, rawType);
		}
		
		var pTestType = (ParameterizedType)testType;
		var pType     = (ParameterizedType)type;
		
		var rawTestType = (Class<?>)pTestType.getRawType();
		var rawType     = (Class<?>)pType.getRawType();
		
		var rawCast = UtilL.instanceOf(rawTestType, rawType);
		if(!rawCast) return false;
		
		Type[] testArgs = pTestType.getActualTypeArguments();
		Type[] args     = pType.getActualTypeArguments();
		
		if(testArgs.length != args.length){
			return false;
		}
		
		for(int i = 0; i<testArgs.length; i++){
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
	
	public static String toShortString(Object o){
		if(o instanceof Stringify s) return s.toShortString();
		return TextUtil.toShortString(o);
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
		var enclosing = clazz.getEnclosingClass();
		if(enclosing != null){
			classToStr(sb, enclosing);
			sb.append('.').append(clazz.getSimpleName());
			return;
		}
		
		var p = clazz.getPackageName();
		for(int i = 0; i<p.length(); i++){
			if(i == 0){
				sb.append(p.charAt(i));
			}else if(p.charAt(i - 1) == '.'){
				sb.append(p, i - 1, i + 1);
			}
		}
		sb.append('.').append(clazz.getSimpleName());
	}
	public static Class<?> getCallee(int depth){
		return getCallee(s -> s.skip(depth + 2));
	}
	public static Class<?> getCallee(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s -> stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	public static StackWalker.StackFrame getFrame(int depth){
		return getFrame(s -> s.skip(depth + 2));
	}
	public static StackWalker.StackFrame getFrame(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s -> stream.apply(s).findFirst().orElseThrow());
	}
	
	
	public static String classNameToHuman(String name){
		int arrayLevels = 0;
		for(int i = 0; i<name.length(); i++){
			if(name.charAt(i) == '[') arrayLevels++;
			else break;
		}
		name = name.substring(arrayLevels);
		if(name.startsWith("L")) name = name.substring(1, name.length() - 1);
		
		var parts = TextUtil.splitByChar(name, '.');
		if(parts.length == 1) name = name + ("[]".repeat(arrayLevels));
		else name = Iters.from(parts)
		                 .limit(parts.length - 1)
		                 .joinAsStr(".", c -> c.charAt(0) + "") +
		            "." + parts[parts.length - 1] +
		            ("[]".repeat(arrayLevels));
		return name;
	}
	public static String typeToHuman(Type type){
		return switch(type){
			case Class<?> c -> classNameToHuman(c.getName());
			case ParameterizedType p -> typeToHuman(p.getRawType()) +
			                            Iters.from(p.getActualTypeArguments()).joinAsStr(", ", "<", ">", Utils::typeToHuman);
			case WildcardType w -> {
				var    lowerBounds = w.getLowerBounds();
				var    bounds      = lowerBounds;
				String ext         = "super";
				if(lowerBounds.length == 0){
					Type[] upperBounds = w.getUpperBounds();
					if(upperBounds.length>0 && !upperBounds[0].equals(Object.class)){
						bounds = upperBounds;
						ext = "extends";
					}else yield "?";
				}
				yield "? " + ext + " " + Iters.from(bounds).joinAsStr(" & ", Utils::typeToHuman);
			}
			case GenericArrayType a -> typeToHuman(a.getGenericComponentType()) + "[]";
			case TypeVariable<?> t -> {
				yield t.getName() + ":" + Iters.from(t.getBounds()).joinAsStr("&", Utils::typeToHuman);
			}
			default -> type.getTypeName();
		};
	}
	
	public static RuntimeException interceptClInit(Throwable e){
		if(StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getMethodName().equals("<clinit>")))){
			Log.warn("CLINIT ERROR: {}", e);
			e.printStackTrace();
		}
		throw UtilL.uncheckedThrow(e);
	}
	
	public static <T> Set<T> join(Set<T> a, Set<T> b){
		if(a.size()<b.size()){
			var tmp = a;
			a = b;
			b = tmp;
		}
		
		int addCount = 0;
		for(T t : b){
			if(!a.contains(t)){
				addCount++;
			}
		}
		
		if(addCount == 0) return a;
		
		var all = HashSet.<T>newHashSet(a.size() + addCount);
		all.addAll(a);
		all.addAll(b);
		return Set.copyOf(all);
	}
	
	public static Optional<Integer> findPathBlockSize(Path path){
		try{
			return Optional.of((int)Math.min(Files.getFileStore(path).getBlockSize(), 1024*1024*64));
		}catch(Throwable e){
			Log.warn("Failed to create fetch chunk size: {}", e);
		}
		return Optional.empty();
	}
	
	public static <T> OptionalLong combineIfBoth(OptionalLong a, OptionalLong b, LongBinaryOperator funct){
		if(a.isEmpty()) return a;
		if(b.isEmpty()) return b;
		return OptionalLong.of(funct.applyAsLong(a.getAsLong(), b.getAsLong()));
	}
	
	public static <T extends Annotation> Optional<T> getAnnotation(Class<?> clazz, Class<T> type){
		return Optional.ofNullable(clazz.getAnnotation(type));
	}
	
	public static void ensureClassLoaded(Class<?> typ){
		try{
			Class.forName(typ.getName(), true, typ.getClassLoader());
		}catch(ClassNotFoundException e){
			throw new ShouldNeverHappenError(e);
		}
	}
	
	/**
	 * To be used only for debugging
	 */
	public static String packDataToBase64(IOInterface data) throws IOException{
		var compressed = IOCompression.Type.GZIP.pack(data.readAll());
		return Base64.getEncoder().encodeToString(compressed);
	}
	
	/**
	 * To be used only for debugging
	 */
	public static byte[] dataFromBase64(String base64){
		var compressed = Base64.getDecoder().decode(base64);
		return IOCompression.Type.GZIP.unpack(compressed);
	}
	
	public static <T> List<T> concat(List<T> a, List<T> b){
		var l = new ArrayList<T>(a.size() + b.size());
		l.addAll(a);
		l.addAll(b);
		return List.copyOf(l);
	}
	public static <T> List<T> concat(List<T> a, T b){
		var l = new ArrayList<T>(a.size() + 1);
		l.addAll(a);
		l.add(b);
		return List.copyOf(l);
	}
	public static boolean isInnerClass(Class<?> clazz){
		return clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers());
	}
	
	public static <T> Optional<T> Some(T val){
		return Optional.of(val);
	}
	public static <T> Optional<T> None(){
		return Optional.empty();
	}
	
	public static Supplier<String> errToStackTraceOnDemand(Throwable e){
		return () -> errToStackTrace(e);
	}
	public static String errToStackTrace(Throwable e){
		var sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}
	
	public static int[] growArr(int[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	public static long[] growArr(long[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	public static <T> T[] growArr(T[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	private static int calcNextSize(int length){
		long nextSizeL = length*2L;
		if(nextSizeL != (int)nextSizeL){
			if(length == Integer.MAX_VALUE){
				throw new OutOfMemoryError();
			}
			nextSizeL = Integer.MAX_VALUE;
		}
		return (int)nextSizeL;
	}
	
	public static OptionalInt longToOptInt(long lSize){
		if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
		return OptionalInt.of((int)lSize);
	}
	public static Class<?> findClosestCommonSuper(IterablePP<Class<?>> classes){
		return classes.reduce(UtilL::findClosestCommonSuper)
		              .orElse(Object.class);
	}
	
	public static String camelCaseToSnakeCase(String s){
		var words = new ArrayList<String>();
		{
			var word = new StringBuilder();
			for(int i = 0; i<s.length(); i++){
				var c = s.charAt(i);
				if(c == '_'){
					if(word.length()>0){
						words.add(word.toString());
						word.setLength(0);
					}
					String prev;
					if(!words.isEmpty() && (prev = words.getLast()).charAt(prev.length() - 1) == '_'){
						prev += '_';
						words.set(words.size() - 1, prev);
					}else words.add("_");
					continue;
				}
				if(Character.isUpperCase(c) && word.length()>0){
					words.add(word.toString());
					word.setLength(0);
				}
				word.append(c);
			}
			if(word.length()>0){
				words.add(word.toString());
			}
		}
		
		//Merge abbreviations. eg: enableHTTPS will result in [enable, HTTPS]
		if(words.size()>1){
			for(int i = 0; i<words.size() - 1; i++){
				var current = words.get(i);
				if(current.length() == 1 && Character.isUpperCase(current.charAt(0))){
					var currentMerge = new StringBuilder(current);
					
					var i1 = i + 1;
					while(words.size()>i1){
						var  other = words.get(i1);
						char c;
						if(other.length() == 1 && Character.isUpperCase(c = other.charAt(0))){
							currentMerge.append(c);
							words.remove(i1);
						}else break;
					}
					
					words.set(i, currentMerge.toString());
				}
			}
		}
		
		for(int i = 0; i<words.size() - 1; i++){
			var word = words.get(i);
			if(!word.isEmpty() && word.charAt(0) == '_'){
				if(word.length() == 1){
					words.remove(i);
					i--;
					continue;
				}else{
					words.set(i, word = word.substring(1));
				}
			}
			if(!word.isEmpty() && word.charAt(word.length() - 1) == '_'){
				words.set(i, word.substring(0, word.length() - 1));
			}
		}
		
		return String.join("_", words);
	}
	
	public static Supplier<String> classPathHeadless(Class<?> typ){
		return () -> {
			var name  = typ.getName();
			var sName = typ.getSimpleName();
			return name.substring(0, name.length() - sName.length());
		};
	}
}
