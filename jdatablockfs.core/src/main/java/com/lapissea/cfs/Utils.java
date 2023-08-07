package com.lapissea.cfs;

import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongBinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.Assert;
import static java.util.stream.Collectors.toUnmodifiableMap;

@SuppressWarnings({"unchecked", "unused"})
public class Utils{
	
	public static void fairDistribute(long[] values, long toDistribute){
		
		long totalUsage = Arrays.stream(values).sum();
		
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
		var bounds = c.getBounds();
		if(bounds.length == 1){
			return bounds[0];
		}
		throw new NotImplementedException(TextUtil.toString("wut? ", bounds));
	}
	
	public static boolean genericInstanceOf(Type testType, Type type){
		if(testType.equals(type)) return true;
		
		if(testType instanceof TypeVariable<?> c) return genericInstanceOf(extractFromVarType(c), type);
		if(type instanceof TypeVariable<?> c) return genericInstanceOf(testType, extractFromVarType(c));
		
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
	
	
	public static String classNameToHuman(String name, boolean doShort){
		int arrayLevels = 0;
		for(int i = 0; i<name.length(); i++){
			if(name.charAt(i) == '[') arrayLevels++;
			else break;
		}
		name = name.substring(arrayLevels);
		if(name.startsWith("L")) name = name.substring(1, name.length() - 1);
		
		
		if(doShort){
			var index = name.lastIndexOf('.');
			name = (index != -1? name.substring(name.lastIndexOf('.')) : name) +
			       ("[]".repeat(arrayLevels));
		}else{
			var parts = TextUtil.splitByChar(name, '.');
			if(parts.length == 1) name = name + ("[]".repeat(arrayLevels));
			else name = Arrays.stream(parts)
			                  .limit(parts.length - 1)
			                  .map(c -> c.charAt(0) + "")
			                  .collect(Collectors.joining(".")) +
			            "." + parts[parts.length - 1] +
			            ("[]".repeat(arrayLevels));
		}
		return name;
	}
	public static String typeToHuman(Type type, boolean doShort){
		return switch(type){
			case Class<?> c -> classNameToHuman(c.getName(), doShort);
			case ParameterizedType p -> typeToHuman(p.getRawType(), doShort) +
			                            Arrays.stream(p.getActualTypeArguments()).map(t -> typeToHuman(t, doShort))
			                                  .collect(Collectors.joining(", ", "<", ">"));
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
				yield "? " + ext + " " + Arrays.stream(bounds).map(b -> typeToHuman(b, doShort))
				                               .collect(Collectors.joining(" & "));
			}
			
			default -> type.getTypeName();
		};
	}
	
	public static RuntimeException interceptClInit(Throwable e){
		if(StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getMethodName().equals("<clinit>")))){
			LogUtil.printlnEr("CLINIT ERROR");
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
	
	public record SealedInstanceUniverse<T extends IOInstance<T>>(
		Class<T> root, Map<Class<T>, StructPipe<T>> pipeMap
	){
		public static <T extends IOInstance<T>> Optional<SealedInstanceUniverse<T>> of(SealedUniverse<T> universe){
			if(IOInstance.isInstance(universe)){
				return Optional.of(new SealedInstanceUniverse<>(universe));
			}
			return Optional.empty();
		}
		
		public SealedInstanceUniverse(SealedUniverse<T> data){
			this(data.root, data.universe.stream().collect(toUnmodifiableMap(t -> t, StandardStructPipe::of)));
		}
		
		public <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> makeSizeDescriptor(boolean nullable, BiFunction<VarPool<Inst>, Inst, T> get){
			
			var sizes     = pipeMap.values().stream().map(StructPipe::getSizeDescriptor).toList();
			var wordSpace = sizes.stream().map(SizeDescriptor::getWordSpace).reduce(WordSpace::min).orElseThrow();
			var fixed = sizes.stream().map(s -> s.getFixed(wordSpace))
			                 .reduce((a, b) -> a.isPresent() && b.isPresent() && a.getAsLong() == b.getAsLong()?
			                                   a : OptionalLong.empty())
			                 .orElseThrow();
			if(fixed.isPresent()){
				return SizeDescriptor.Fixed.of(wordSpace, fixed.getAsLong());
			}
			
			var minSize = nullable? 0 : sizes.stream().mapToLong(s -> s.getMin(wordSpace)).min().orElseThrow();
			var maxSize = sizes.stream().map(s -> s.getMax(wordSpace)).reduce((a, b) -> Utils.combineIfBoth(a, b, Math::max)).orElseThrow();
			
			return SizeDescriptor.Unknown.of(
				wordSpace, minSize, maxSize,
				(ioPool, prov, inst) -> {
					T val = get.apply(ioPool, inst);
					if(val == null){
						if(!nullable) throw new NullPointerException();
						return 0;
					}
					StructPipe<T> instancePipe = pipeMap.get(val.getClass());
					
					return instancePipe.calcUnknownSize(prov, val, wordSpace);
				}
			);
		}
		
		public boolean calcCanHavePointers(){
			return pipeMap.values().stream()
			              .map(StructPipe::getType)
			              .anyMatch(Struct::getCanHavePointers);
		}
	}
	
	public record SealedUniverse<T>(Class<T> root, Set<Class<T>> universe){
		public SealedUniverse{
			Objects.requireNonNull(root);
			assert root.isSealed();
			universe = Set.copyOf(universe);
		}
	}
	
	public static <T> Optional<SealedUniverse<T>> getSealedUniverse(Class<T> type, boolean allowUnbounded){
		return computeSealedUniverse(type, allowUnbounded);
	}
	
	private static <T> Optional<SealedUniverse<T>> computeSealedUniverse(Class<T> type, boolean allowUnbounded){
		if(!type.isSealed()){
			return Optional.empty();
		}
		var universe = new HashSet<Class<T>>();
		if(!type.isInterface() && !Modifier.isAbstract(type.getModifiers())){
			universe.add(type);
		}
		var psbc = type.getPermittedSubclasses();
		for(var sub : (Class<T>[])psbc){
			if(sub.isSealed()){
				var uni = computeSealedUniverse(sub, allowUnbounded);
				if(uni.isEmpty()) return Optional.empty();
				universe.addAll(uni.get().universe);
				continue;
			}
			if(allowUnbounded || Modifier.isFinal(sub.getModifiers())){
				universe.add(sub);
				continue;
			}
			//Non sealed make for an unbounded universe
			return Optional.empty();
		}
		if(universe.isEmpty()) throw new IllegalStateException();
		return Optional.of(new SealedUniverse<>(type, universe));
	}
	
	public static <T extends Annotation> Optional<T> getAnnotation(Class<?> clazz, Class<T> type){
		return Optional.ofNullable(clazz.getAnnotation(type));
	}
}
