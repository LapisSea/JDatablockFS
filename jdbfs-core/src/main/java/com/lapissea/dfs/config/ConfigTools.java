package com.lapissea.dfs.config;

import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.util.ConsoleColors.*;

public final class ConfigTools{
	
	static final class Dummy implements ConfigDefs{ }
	
	public sealed interface DefaultValue<T>{
		record OtherFlagFallback<T>(Flag<T> flag) implements DefaultValue<T>{
			public OtherFlagFallback{ Objects.requireNonNull(flag); }
			@Override
			public T value(){ return flag.defaultValue.value(); }
		}
		
		record Literal<T>(T value) implements DefaultValue<T>{
			public Literal{ Objects.requireNonNull(value); }
		}
		
		record Lambda<T>(Supplier<T> valueMaker) implements DefaultValue<T>{
			public Lambda{ Objects.requireNonNull(valueMaker); }
			@Override
			public T value(){ return valueMaker.get(); }
		}
		
		T value();
	}
	
	/**
	 * Instructions on how to aqquire a flag by its name and now to fall back on to its default value
	 */
	public abstract static sealed class Flag<T>{
		
		private static final class NValidate<T> implements Function<T, String>{
			private final List<Function<T, String>> fns;
			private NValidate(Iterable<Function<T, String>> fns){ this.fns = Iters.from(fns).toList(); }
			
			@Override
			public String apply(T t){
				for(var fn : fns){
					var err = fn.apply(t);
					if(err != null) return err;
				}
				return null;
			}
		}
		
		private static <T> Function<T, String> validateBoth(Function<T, String> a, Function<T, String> b){
			Flag.NValidate<T>   nv;
			Function<T, String> other;
			if(a instanceof Flag.NValidate<T> nv0){
				nv = nv0;
				other = b;
			}else if(b instanceof Flag.NValidate<T> nv0){
				nv = nv0;
				other = a;
			}else return new NValidate<>(List.of(a, b));
			return new NValidate<>(Iters.concatN1(nv.fns, other));
		}
		
		public static final class FBool extends Flag<Boolean>{
			
			public FBool(String name, DefaultValue<Boolean> defaultValue){ super(name, defaultValue); }
			
			/**
			 * @see FBool#resolveVal()
			 */
			@Override
			@Deprecated
			public Boolean resolve(){ return resolveVal(); }
			public boolean resolveVal(){
				return ConfigUtils.configBoolean(name, defaultValue.value());
			}
			
			public boolean resolveValLocking(){
				lock();
				return ConfigUtils.configBoolean(name, defaultValue.value());
			}
			
			public <U> Supplier<U> boolMap(U ifTrue, U ifFalse){
				return () -> resolveVal()? ifTrue : ifFalse;
			}
		}
		
		public static final class FInt extends Flag<Integer>{
			private final Function<Integer, String> validate;
			
			public FInt(String name, DefaultValue<Integer> defaultValue, Function<Integer, String> validate){
				super(name, defaultValue);
				this.validate = validate;
				
				if(validate != null){
					var def  = defaultValue.value();
					var derr = validate.apply(def);
					if(derr != null) throw new IllegalStateException("Default value not valid: " + derr);
				}
			}
			@Override
			public void set(Integer val) throws LockedFlagSet{
				if(validate != null){
					var err = validate.apply(val);
					if(err != null){
						throw new IllegalArgumentException(name + " = " + val + " Reason: " + err);
					}
				}
				super.set(val);
			}
			
			/**
			 * @see FInt#resolveVal()
			 */
			@Override
			@Deprecated
			public Integer resolve(){ return resolveVal(); }
			public int resolveVal(){
				int def = defaultValue.value();
				int val = ConfigUtils.configInt(name, def);
				if(validate != null){
					var err = validate.apply(val);
					if(err != null){
						Log.warn("\"{}\" is not a valid value for {}. Reason: {}", val, name, err);
						return def;
					}
				}
				return val;
			}
			public int resolveValLocking(){
				lock();
				return resolveVal();
			}
			public FInt natural(){
				return withValidation(val -> {
					if(val<=0) return "Value must be greater than 0!";
					return null;
				});
			}
			public FInt positiveOptional(){
				return withValidation(val -> {
					if(val<0 && val != -1) return "Value must be positive or -1!";
					return null;
				});
			}
			public FInt positive(){
				return withValidation(val -> {
					if(val<0) return "Value must be positive!";
					return null;
				});
			}
			public FInt withValidation(Function<Integer, String> validate){
				Objects.requireNonNull(validate);
				return new FInt(name, defaultValue, this.validate == null? validate : validateBoth(this.validate, validate));
			}
		}
		
		public static final class FDur extends Flag<Duration>{
			private final Function<Duration, String> validate;
			
			public FDur(String name, DefaultValue<Duration> defaultValue, Function<Duration, String> validate){
				super(name, defaultValue);
				this.validate = validate;
				
				if(validate != null){
					var def  = defaultValue.value();
					var derr = validate.apply(def);
					if(derr != null) throw new IllegalStateException("Default value not valid: " + derr);
				}
			}
			
			@Override
			public void set(Duration val) throws LockedFlagSet{
				if(validate != null){
					var err = validate.apply(val);
					if(err != null){
						throw new IllegalArgumentException("Default value not valid: " + val + "\n\tReason: " + err);
					}
				}
				checkLocked();
				System.setProperty(name(), durToStr(val));
			}
			
			@Override
			public Duration resolve(){
				var def = defaultValue.value();
				var val = ConfigUtils.configDuration(name, def);
				if(validate != null){
					var err = validate.apply(val);
					if(err != null){
						Log.warn("\"{}\" is not a valid value for {}. Reason: {}", val, name, err);
						return def;
					}
				}
				return val;
			}
			
			public FDur limitMaxNs(long nanos){
				return limitMax(Duration.ofNanos(nanos));
			}
			public FDur limitMaxMs(long milis){
				return limitMax(Duration.ofMillis(milis));
			}
			public FDur limitMax(Duration max){
				Objects.requireNonNull(max);
				return withValidation(val -> {
					if(val != null && val.compareTo(max)>0) return "Value be smaller or equal to " + max + "!";
					return null;
				});
			}
			
			public FDur positive(){
				return withValidation(val -> {
					if(val != null && val.isNegative()) return "Value must be positive!";
					return null;
				});
			}
			
			public FDur withValidation(Function<Duration, String> validate){
				Objects.requireNonNull(validate);
				return new FDur(name, defaultValue, this.validate == null? validate : validateBoth(this.validate, validate));
			}
			@Override
			public String resolveAsStr(){
				return durToStr(resolve());
			}
			private static String durToStr(Duration dur){
				if(dur.isZero()) return "0 ms";
				try{
					long val;
					if(dur.equals(Duration.ofHours(val = dur.toHours()))) return val + " hours";
					if(dur.equals(Duration.ofMinutes(val = dur.toMinutes()))) return val + " minutes";
					if(dur.equals(Duration.ofSeconds(val = dur.toSeconds()))) return val + "sec";
					if(dur.equals(Duration.ofMillis(val = dur.toMillis()))) return val + "ms";
					if(dur.equals(Duration.ofNanos(val = dur.toNanos()))) return val + "ns";
				}catch(ArithmeticException ignore){ }
				return dur.toString().replace("PT", "");
			}
		}
		
		public static final class FStr extends Flag<String>{
			
			public FStr(String name, DefaultValue<String> defaultValue){ super(name, defaultValue); }
			
			@Override
			public String resolve(){
				return ConfigUtils.optionalProperty(name).orElseGet(defaultValue::value);
			}
		}
		
		public static final class FStrOptional extends Flag<Optional<String>>{
			
			private static final DefaultValue<Optional<String>> DEFAULT_VALUE = new DefaultValue.Literal<>(Optional.empty());
			
			public FStrOptional(String name){ super(name, DEFAULT_VALUE); }
			
			@Override
			public Optional<String> resolve(){
				return ConfigUtils.optionalProperty(name);
			}
		}
		
		public static final class FEnum<E extends Enum<E>> extends Flag<E>{
			
			public FEnum(String name, DefaultValue<E> defaultValue){ super(name, defaultValue); }
			
			@Override
			public E resolve(){
				return ConfigUtils.configEnum(name, defaultValue.value());
			}
		}
		
		public final String          name;
		public final DefaultValue<T> defaultValue;
		private      boolean         locked;
		private      T               lockedValue;
		private      Throwable       lockTrace;
		
		protected Flag(String name, DefaultValue<T> defaultValue){
			this.name = Objects.requireNonNull(name);
			this.defaultValue = Objects.requireNonNull(defaultValue);
			if(name.isBlank()){
				throw new IllegalArgumentException("Name must not be empty or blank");
			}
		}
		
		public T resolveLocking(){
			lock();
			return lockedValue;
		}
		public abstract T resolve();
		
		public String resolveAsStr(){
			return Objects.toString(locked? lockedValue : resolve());
		}
		
		public void set(T val) throws LockedFlagSet{
			checkLocked();
			System.setProperty(name(), Objects.toString(val));
		}
		
		protected void checkLocked() throws LockedFlagSet{
			if(locked){
				synchronized(this){
					throw new LockedFlagSet(this, lockTrace);
				}
			}
		}
		
		public synchronized void lock(){
			if(locked) return;
			lockedValue = resolve();
			locked = true;
			
			var cname = RED + name + RESET;
			
			lockTrace = new Throwable("Locked flag: " + cname);
			var trace = lockTrace.getStackTrace();
			
			var count = 0;
			for(var e : trace){
				if(e.getMethodName().equals("<clinit>")) break;
				if(!e.getClassName().startsWith(Flag.class.getName())) return;
				count++;
			}
			if(count == trace.length) return;
			
			var classTrace = new StackTraceElement[trace.length - count];
			System.arraycopy(trace, count, classTrace, 0, classTrace.length);
			
			lockTrace = new Throwable("Flag " + cname + " locked because class " + RED + trace[count].getClassName() + RESET + " loaded");
			lockTrace.setStackTrace(classTrace);
		}
		
		public <U> Supplier<U> map(Function<T, U> mapper){
			return () -> mapper.apply(resolve());
		}
		
		public static final class TempFlagVal<T> implements AutoCloseable{
			public final  T       oldValue;
			private final Flag<T> flag;
			private TempFlagVal(Flag<T> flag, T oldValue){
				this.oldValue = oldValue;
				this.flag = flag;
			}
			@Override
			public void close() throws LockedFlagSet{
				flag.set(oldValue);
			}
		}
		
		
		public TempFlagVal<T> temporarySet(T val) throws LockedFlagSet{
			var old = resolve();
			set(val);
			return new TempFlagVal<>(this, old);
		}
		
		public final String name(){ return name; }
		
		@Override
		public final String toString(){
			var isDef = Objects.equals(resolve(), defaultValue.value());
			return "Flag{" + name() + " : " + resolveAsStr() + (isDef? " (default)" : "") + (locked? ", locked" : "") + "}";
		}
		@Override
		public final int hashCode(){
			return name().hashCode();
		}
		@Override
		public final boolean equals(Object obj){
			if(obj.getClass() != this.getClass()) return false;
			var that = (Flag<?>)obj;
			return this.name().equals(that.name());
		}
	}
	
	public static Flag.FInt flagI(String name, Flag.FInt defaultVal)        { return flagInt(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FInt flagI(String name, int defaultVal)              { return flagInt(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FInt flagI(String name, Supplier<Integer> valueMaker){ return flagInt(name, new DefaultValue.Lambda<>(valueMaker)); }
	public static Flag.FInt flagInt(String name, DefaultValue<Integer> defaultVal){
		return new Flag.FInt(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal, null);
	}
	
	public static Flag.FDur flagDur(String name, Flag.FDur defaultVal)         { return flagDuration(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FDur flagDur(String name, Duration defaultVal)          { return flagDuration(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FDur flagDur(String name, Supplier<Duration> valueMaker){ return flagDuration(name, new DefaultValue.Lambda<>(valueMaker)); }
	public static Flag.FDur flagDuration(String name, DefaultValue<Duration> defaultVal){
		return new Flag.FDur(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal, null);
	}
	
	public static Flag.FBool flagB(String name, Flag.FBool defaultVal)       { return flagBool(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FBool flagB(String name, boolean defaultVal)          { return flagBool(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FBool flagB(String name, Supplier<Boolean> valueMaker){ return flagBool(name, new DefaultValue.Lambda<>(valueMaker)); }
	public static Flag.FBool flagBool(String name, DefaultValue<Boolean> defaultVal){
		return new Flag.FBool(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal);
	}
	
	public static Flag.FStr flagS(String name, Flag.FStr defaultVal)       { return flagStr(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static Flag.FStr flagS(String name, String defaultVal)          { return flagStr(name, new DefaultValue.Literal<>(defaultVal)); }
	public static Flag.FStr flagS(String name, Supplier<String> valueMaker){ return flagStr(name, new DefaultValue.Lambda<>(valueMaker)); }
	public static Flag.FStr flagStr(String name, DefaultValue<String> defaultVal){
		return new Flag.FStr(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal);
	}
	
	public static Flag.FStrOptional flagS(String name){
		return new Flag.FStrOptional(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name));
	}
	
	///
	
	public static <T extends Enum<T>> Flag.FEnum<T> flagEnum(String name, DefaultValue<T> defaultVal){ return new Flag.FEnum<>(ConfigDefs.CONFIG_PROPERTY_PREFIX + Objects.requireNonNull(name), defaultVal); }
	public static <T extends Enum<T>> Flag.FEnum<T> flagE(String name, Flag.FEnum<T> defaultVal){ return flagEnum(name, new DefaultValue.OtherFlagFallback<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.FEnum<T> flagEV(String name, T defaultVal)           { return flagEnum(name, new DefaultValue.Literal<>(defaultVal)); }
	public static <T extends Enum<T>> Flag.FEnum<T> flagE(String name, Supplier<T> valueMaker)  { return flagEnum(name, new DefaultValue.Lambda<>(valueMaker)); }
	
	
	public record ConfEntry(String name, String val){
		public static ConfEntry checked(String name, String val){
			if(!name.startsWith(ConfigDefs.CONFIG_PROPERTY_PREFIX)){
				throw new IllegalArgumentException(name + " does not start with " + ConfigDefs.CONFIG_PROPERTY_PREFIX);
			}
			return new ConfEntry(name, val);
		}
		public ConfEntry{
			Objects.requireNonNull(name);
			Objects.requireNonNull(val);
		}
	}
	
	public static String configFlagsToTable(Collection<ConfEntry> values, int padding, boolean grouping){
		var padStr = " ".repeat(padding);
		
		var nameLen = Iters.from(values).map(ConfEntry::name).mapToInt(String::length).max(0);
		
		var singles = new ArrayList<ConfEntry>();
		var groupsE = new ArrayList<Map.Entry<String, List<ConfEntry>>>();
		if(grouping){
			var groups = Iters.from(values).toGrouping(e -> e.name.split("\\.")[1]);
			for(var e : groups.entrySet()){
				if(e.getValue().size() == 1){
					singles.add(e.getValue().getFirst());
				}else{
					groupsE.add(e);
				}
			}
		}else{
			singles.addAll(values);
		}
		singles.sort(Comparator.comparing(e -> e.name));
		groupsE.sort(Map.Entry.comparingByKey());
		
		
		StringBuilder sb = new StringBuilder();
		for(var e : singles){
			var name = e.name;
			var val  = e.val;
			sb.append(padStr);
			var dotPos = name.indexOf('.');
			if(dotPos == -1) sb.append(name);
			else{
				sb.append(BLACK_BRIGHT).append(name, 0, dotPos + 1).append(RESET)
				  .append(name, dotPos + 1, name.length());
			}
			sb.append(" ".repeat(nameLen - name.length())).append(": ")
			  .append(val).append('\n');
		}
		
		for(var group : groupsE){
			var gName    = group.getKey();
			var elements = group.getValue();
			int pad      = nameLen - gName.length() - 2, before = pad/2, after = pad - before;
			sb.append(padStr).append("-".repeat(before)).append(' ')
			  .append(gName.length()>2? TextUtil.firstToUpperCase(gName) : gName).append(' ')
			  .append("-".repeat(after)).append('\n');
			
			var len = Iters.from(elements.getFirst().name.split("\\.")).limit(2).mapToInt(s -> s.length() + 1).sum();
			for(var e : elements){
				var name     = e.name;
				var val      = e.val;
				var segments = name.split("\\.");
				sb.append(padStr);
				if(segments.length == 2){
					sb.append(name);
				}else{
					sb.append(BLACK_BRIGHT).append(name, 0, len).append(RESET).append(name, len, name.length());
				}
				sb.append(" ".repeat(nameLen - name.length())).append(": ")
				  .append(val).append('\n');
			}
		}
		
		return sb.toString();
	}
	
	public static List<ConfEntry> collectConfigFlags(){
		return configFlagFields().toModList(val -> {
			var name = val.name();
			return ConfEntry.checked(name, switch(val){
				case Flag.FEnum<?> enumFlag -> {
					var enums = enumFlag.defaultValue.value().getClass().getEnumConstants();
					var enumStr = Iters.from(enums).joinAsStr(", ", "[", "]", e -> {
						if(e instanceof NamedEnum ne){
							return String.join(" / ", ne.names());
						}
						return e.toString();
					});
					yield PURPLE_BRIGHT + val.resolveAsStr() + RESET + " - " + PURPLE + enumStr + RESET;
				}
				case Flag.FBool bool -> BLUE + bool.resolveAsStr() + RESET;
				case Flag.FInt anInt -> YELLOW_BRIGHT + anInt.resolveAsStr() + RESET;
				case Flag.FStr str -> PURPLE_BRIGHT + str.resolveAsStr() + RESET;
				case Flag.FStrOptional str -> str.resolve().map(v -> PURPLE + v + RESET).orElse("");
				case Flag.FDur dur -> CYAN_BRIGHT + dur.resolveAsStr() + RESET;
			});
		});
	}
	public static IterablePP<Flag<?>> configFlagFields(){
		return Iters.from(ConfigDefs.class.getDeclaredFields()).filter(field -> UtilL.instanceOf(field.getType(), Flag.class)).map(field -> {
			try{
				var obj = (Flag<?>)field.get(null);
				if(obj == null) throw new NullPointerException(field + " is null");
				return obj;
			}catch(IllegalAccessException e){
				throw new ShouldNeverHappenError(e);
			}
		});
	}
	
}
