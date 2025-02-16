package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.run.TempClassGen.VisiblityGen;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.fuzz.RunMark;
import com.lapissea.util.LogUtil;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.random.RandomGenerator;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;

public final class StructFuzzTest{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	private static final List<BiFunction<String, RandomGenerator, TempClassGen.FieldGen>> fieldTypes = List.of(
		StructFuzzTest::signedInt,
		StructFuzzTest::unsignedInt,
		StructFuzzTest::string,
		StructFuzzTest::generic,
		StructFuzzTest::wrapper
	);
	
	private static final Annotation
		IOVal    = Annotations.make(IOValue.class),
		Unsigned = Annotations.make(IOValue.Unsigned.class),
		Gen      = Annotations.make(IOValue.Generic.class),
		vns      = Annotations.make(IODependency.VirtualNumSize.class),
		Nullable = Annotations.make(IONullability.class, Map.of("value", IONullability.Mode.NULLABLE));
	
	private static Annotation opt(RandomGenerator rand, Annotation ann){
		return rand.nextBoolean()? ann : null;
	}
	private static List<Annotation> anns(Annotation... anns){
		return Iters.of(anns).nonNulls().toList();
	}
	
	private static boolean isFinal(RandomGenerator rand){
		return rand.nextBoolean();
	}
	
	private static TempClassGen.FieldGen signedInt(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name + "_int", getVisibility(rand), isFinal(rand), int.class, anns(IOVal, opt(rand, vns)),
			RandomGenerator::nextInt
		);
	}
	
	private static final List<VisiblityGen> ALL_VIS = List.copyOf(EnumSet.allOf(VisiblityGen.class));
	private static VisiblityGen getVisibility(RandomGenerator rand){
		if(rand.nextInt(5) == 0){
			return VisiblityGen.PUBLIC;
		}
		return ALL_VIS.get(rand.nextInt(ALL_VIS.size()));
	}
	
	private static TempClassGen.FieldGen unsignedInt(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name + "_uint", getVisibility(rand), isFinal(rand), int.class, anns(IOVal, Unsigned, opt(rand, vns)),
			r -> r.nextInt(Integer.MAX_VALUE)
		);
	}
	
	private static TempClassGen.FieldGen string(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name + "_str", getVisibility(rand), isFinal(rand), String.class, anns(IOVal, opt(rand, Nullable)), StructFuzzTest::randStr
		);
	}
	private static String randStr(RandomGenerator r){
		return Iters.rand(r, r.nextInt(50), 'a', 255)
		            .mapToObj(i -> Character.toString((char)i)).joinAsStr();
	}
	private static TempClassGen.FieldGen generic(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name + "_gen", getVisibility(rand), isFinal(rand), Object.class, anns(IOVal, Gen, opt(rand, Nullable)),
			r -> switch(rand.nextInt(3)){
				case 0 -> rand.nextInt();
				case 1 -> "hello world";
				case 2 -> NumberSize.SHORT;
				default -> throw new IllegalStateException("Unexpected value: " + rand.nextInt(3));
			}
		);
	}
	
	private static final Set<Class<?>> UNKNOWNS = Collections.synchronizedSet(new HashSet<>());
	private static TempClassGen.FieldGen wrapper(String name, RandomGenerator rand){
		var t     = Iters.from(FieldCompiler.getWrapperTypes()).filter(c -> c != String.class).toModList();
		var type  = t.get(rand.nextInt(t.size()));
		var nulls = rand.nextBoolean();
		
		return new TempClassGen.FieldGen(
			name + "_" + type.getSimpleName().replace("[]", "_arr"),
			getVisibility(rand), isFinal(rand), type, anns(IOVal, nulls? Nullable : null),
			r -> {
				if(nulls && r.nextBoolean()) return null;
				
				if(type == Duration.class) return Duration.ofMillis(r.nextInt());
				if(type == Instant.class) return Instant.ofEpochSecond(r.nextInt());
				if(type == LocalTime.class) return localTime(r);
				if(type == LocalDate.class) return localDate(r);
				if(type == LocalDateTime.class) return LocalDateTime.of(localDate(r), localTime(r));
				if(type == byte[].class){
					var bb = new byte[r.nextInt(10)];
					r.nextBytes(bb);
					return bb;
				}
				if(type == boolean[].class) return bools(r);
				if(type == int[].class){
					var bb = new int[r.nextInt(10)];
					for(int i = 0; i<bb.length; i++) bb[i] = r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
					return bb;
				}
				if(type == float[].class){
					var bb = new float[r.nextInt(10)];
					for(int i = 0; i<bb.length; i++) bb[i] = r.nextFloat();
					return bb;
				}
				if(type == ByteBuffer.class){
					var data = new byte[r.nextInt(10)];
					r.nextBytes(data);
					var bb = ByteBuffer.wrap(data);
					if(rand.nextBoolean()) bb = bb.asReadOnlyBuffer();
					return bb;
				}
				if(UNKNOWNS.add(type)){
					LogUtil.println("Unknown wrapper type", type.getTypeName());
				}
				return null;
			}
		);
	}
	private static LocalTime localTime(RandomGenerator r){
		return LocalTime.ofNanoOfDay(r.nextLong(86400L*1000_000_000L - 1));
	}
	private static LocalDate localDate(RandomGenerator r){
		return LocalDate.ofEpochDay(r.nextLong(-365243219162L, 365241780471L));
	}
	private static boolean[] bools(RandomGenerator r){
		var bb = new boolean[r.nextInt(10)];
		for(int i = 0; i<bb.length; i++) bb[i] = r.nextBoolean();
		return bb;
	}
	
	@org.testng.annotations.DataProvider
	Object[][] finVis(){
		return Iters.from(VisiblityGen.class)
		            .flatMap(v -> List.of(new Object[]{true, v}, new Object[]{false, v}))
		            .toArray(Object[].class);
	}
	
	private final Set<TempClassGen.ClassGen> simpleEncounter = new HashSet<>();
	
	@Test(groups = "earlyCheck", dataProvider = "finVis")
	void simpleGenClass(boolean isFinal, VisiblityGen visiblity) throws ReflectiveOperationException, IOException{
		var gen = new TempClassGen.ClassGen(
			"testBefore",
			List.of(new TempClassGen.FieldGen("f1", visiblity, isFinal, int.class, anns(IOVal), RandomGenerator::nextInt)),
			Set.of(new TempClassGen.CtorType.All(), new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class,
			List.of()
		);
		testType(gen);
		simpleEncounter.add(gen);
	}
	
	@Test(groups = "earlyCheck", dataProvider = "finVis")
	void twoStringsSNullable(boolean isFinal, VisiblityGen visiblity) throws ReflectiveOperationException, IOException{
		
		var gen = new TempClassGen.ClassGen(
			"TwoStrings",
			List.of(
				new TempClassGen.FieldGen("s1", visiblity, isFinal, String.class, anns(IOVal), r -> "A"),
				new TempClassGen.FieldGen("s2", visiblity, isFinal, String.class, anns(IOVal, Nullable), r -> "š")
			),
			Set.of(new TempClassGen.CtorType.All(), new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class,
			List.of(Annotations.make(IOInstance.Order.class, Map.of("value", new String[]{"s1", "s2"})))
		);
		testType(gen);
		simpleEncounter.add(gen);
	}
	@Test(groups = "earlyCheck")
	void instant() throws ReflectiveOperationException, IOException{
		
		var gen = new TempClassGen.ClassGen(
			"InstantTyp",
			List.of(
				new TempClassGen.FieldGen("i1", VisiblityGen.PUBLIC, false, Instant.class, anns(IOVal, Nullable),
				                          r -> Instant.ofEpochSecond(r.nextInt(1000)))
			),
			Set.of(new TempClassGen.CtorType.All(), new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class,
			List.of()
		);
		testType(gen);
		simpleEncounter.add(gen);
	}
	@Test(groups = "earlyCheck")
	void hundredFields() throws ReflectiveOperationException, IOException{
		var gen = tryCreate(new RawRandom(1000), 1, 100);
		testType(gen);
		simpleEncounter.add(gen);
	}
	
	@Test(dependsOnGroups = "earlyCheck")
	public void fuzzGen() throws LockedFlagSet{
		
		var sequenceSource = new FuzzSequenceSource.LenSeed(69420, 5_000, 1);
		
		Set<TempClassGen.ClassGen> encountered = Collections.newSetFromMap(new ConcurrentHashMap<>());
		var runner = new FuzzingRunner<>(new FuzzingStateEnv.Marked<TempClassGen.ClassGen, Object, IOException>(){
			
			{ encountered.addAll(simpleEncounter); }
			
			@Override
			public void applyAction(TempClassGen.ClassGen state, long actionIndex, Object action, RunMark mark) throws IOException{
				var stateDash = state.withName("_");
				if(encountered.contains(stateDash)) return;
				
				if(mark.hasSequence()){
					System.gc();
					try{
						ConfigDefs.PRINT_COMPILATION.set(ConfigDefs.CompLogLevel.FULL);
					}catch(LockedFlagSet e){ }
					LogUtil.println(state);
					int i = 0;
				}
				
				try{
					testType(state);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
				encountered.add(stateDash);
			}
			
			@Override
			public TempClassGen.ClassGen create(RandomGenerator rand, long sequenceIndex, RunMark mark){
				for(int i = 0; i<9; i++){
					var gen = tryCreate(rand, sequenceIndex, randFieldCount(rand, sequenceIndex));
					if(!encountered.contains(gen.withName("_"))){
						return gen;
					}
				}
				return tryCreate(rand, sequenceIndex, randFieldCount(rand, sequenceIndex));
			}
			private int randFieldCount(RandomGenerator rand, long sequenceIndex){
				var fac        = sequenceIndex/(double)sequenceSource.totalIterations();
				var fieldCount = 1 + rand.nextInt((int)(fac*7) + 1);
				if(sequenceIndex == 1) fieldCount = 0;
				return fieldCount;
			}
		}, FuzzingRunner::noopAction);
		
		
		try(var ignore = ConfigDefs.PRINT_COMPILATION.temporarySet(ConfigDefs.CompLogLevel.NONE)){
			FuzzingUtils.stableRun(Plan.start(
				runner,
				sequenceSource
//				() -> Stream.of(FuzzSequence.fromDataStick("IhiDAoMCEwMzGt0SxnwB"))
			), "fuzzChainResize");
		}
		LogUtil.println(encountered.size(), sequenceSource.totalIterations(), encountered.size()/(double)sequenceSource.totalIterations()*100);
	}
	
	public <T extends IOInstance<T>> void testType(TempClassGen.ClassGen def) throws IOException, ReflectiveOperationException{
//		LogUtil.println(def);
//		LogUtil.println(def.name());
		
		//noinspection unchecked
		var typ    = (Class<T>)TempClassGen.gen(def);
		var struct = Struct.of(typ, STATE_DONE);
		var pipe   = StandardStructPipe.of(struct, STATE_DONE);
		
		var fs   = Iters.from(def.fields());
		var rand = new RawRandom(def.name().hashCode());
		var ctor = typ.getConstructor(fs.map(TempClassGen.FieldGen::type).toArray(Class[]::new));
		
		
		var mem = Cluster.emptyMem();
		var ch  = AllocateTicket.bytes(64).submit(mem);
		
		var sets = struct.getRealFields().mapped(FieldSet::of).toList();
		for(int i = 0; i<50; i++){
			var inst = ctor.newInstance(fs.map(t -> t.generator().apply(rand)).toArray(Object[]::new));
			TestUtils.checkPipeInOutEquality(ch, pipe, inst);
			
			for(var fs1 : sets){
				TestUtils.checkPipeInOutEquality(ch, pipe, inst, fs1);
			}
		}
	}
	
	private TempClassGen.ClassGen tryCreate(RandomGenerator rand, long sequenceIndex, int fieldCount){
		if(rand.nextFloat()>0.99) fieldCount *= 10;
		
		var fields = Iters.rand(rand, fieldCount, 0, fieldTypes.size()).enumerate()
		                  .toList(e -> fieldTypes.get(e.val()).apply("field" + e.index(), rand));
		
		boolean hasFinal = Iters.from(fields).anyMatch(TempClassGen.FieldGen::isFinal);
		
		Set<TempClassGen.CtorType> constructors = new HashSet<>();
		if(!fields.isEmpty()) constructors.add(new TempClassGen.CtorType.All());
		if(Iters.from(fields).noneMatch(TempClassGen.FieldGen::isFinal)) constructors.add(new TempClassGen.CtorType.Empty());
		if(!hasFinal) constructors.add(new TempClassGen.CtorType.Empty());
		
		List<Annotation> annotations;
		if(hasFinal){
			annotations = List.of(IOFieldTools.orderFromNames(Iters.from(fields).map(TempClassGen.FieldGen::name)));
		}else{
			annotations = List.of();
		}
		
		return new TempClassGen.ClassGen(
			"test" + sequenceIndex,
			fields,
			constructors,
			IOInstance.Managed.class,
			annotations
		);
	}
}
