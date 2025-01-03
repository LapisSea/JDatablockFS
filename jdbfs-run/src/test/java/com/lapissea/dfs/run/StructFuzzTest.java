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
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.fuzz.RunMark;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
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
	
	private static final List<BiFunction<String, RandomGenerator, TempClassGen.FieldGen>> fieldTypes = List.of(
		StructFuzzTest::signedInt,
		StructFuzzTest::unsignedInt,
		StructFuzzTest::string,
		StructFuzzTest::generic
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
			name, getVisibility(rand), isFinal(rand), int.class, anns(IOVal, opt(rand, vns)),
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
			name, getVisibility(rand), isFinal(rand), int.class, anns(IOVal, Unsigned, opt(rand, vns)),
			r -> r.nextInt(Integer.MAX_VALUE)
		);
	}
	
	private static TempClassGen.FieldGen string(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name, getVisibility(rand), isFinal(rand), String.class, anns(IOVal, opt(rand, Nullable)),
			r -> Iters.rand(r, r.nextInt(100), 0, 255)
			          .mapToObj(i -> Character.toString((char)i)).joinAsStr()
		);
	}
	private static TempClassGen.FieldGen generic(String name, RandomGenerator rand){
		return new TempClassGen.FieldGen(
			name, getVisibility(rand), isFinal(rand), Object.class, anns(IOVal, Gen, opt(rand, Nullable)),
			r -> switch(rand.nextInt(3)){
				case 0 -> rand.nextInt();
				case 1 -> "hello world";
				case 2 -> NumberSize.SHORT;
				default -> throw new IllegalStateException("Unexpected value: " + rand.nextInt(3));
			}
		);
	}
	
	@org.testng.annotations.DataProvider
	Object[][] yn(){
		return Iters.from(VisiblityGen.class)
		            .flatMap(v -> List.of(new Object[]{true, v}, new Object[]{false, v}))
		            .toArray(Object[].class);
	}
	
	private final Set<TempClassGen.ClassGen> simpleEncounter = new HashSet<>();
	
	@Test(dataProvider = "yn")
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
	
	@Test(dependsOnMethods = "simpleGenClass")
	public void fuzzGen() throws LockedFlagSet{
		var runner = new FuzzingRunner<>(new FuzzingStateEnv.Marked<TempClassGen.ClassGen, Object, IOException>(){
			private final Set<TempClassGen.ClassGen> encountered = Collections.newSetFromMap(new ConcurrentHashMap<>());
			
			{ encountered.addAll(simpleEncounter); }
			
			@Override
			public void applyAction(TempClassGen.ClassGen state, long actionIndex, Object action, RunMark mark) throws IOException{
				if(!encountered.add(state.withName("_"))) return;
				try{
					testType(state);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			}
			
			@Override
			public TempClassGen.ClassGen create(RandomGenerator rand, long sequenceIndex, RunMark mark){
				for(int i = 0; i<9; i++){
					var gen = tryCreate(rand, sequenceIndex, mark);
					if(!encountered.contains(gen.withName("_"))){
						return gen;
					}
				}
				return tryCreate(rand, sequenceIndex, mark);
			}
			private TempClassGen.ClassGen tryCreate(RandomGenerator rand, long sequenceIndex, RunMark mark){
				var fieldCount = rand.nextInt(6);
				
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
		}, FuzzingRunner::noopAction);
		
		try(var ignore = ConfigDefs.PRINT_COMPILATION.temporarySet(ConfigDefs.CompLogLevel.NONE)){
			Plan.start(
				runner, new FuzzConfig().withName("fuzzChainResize"),
				new FuzzSequenceSource.LenSeed(69420, 10_000, 1)
//				() -> Stream.of(FuzzSequence.fromDataStick("REPLACE_ME"))
			).runAll().assertFail();
		}
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
		
		for(int i = 0; i<50; i++){
			var inst = ctor.newInstance(fs.map(t -> t.generator().apply(rand)).toArray(Object[]::new));
			TestUtils.checkPipeInOutEquality(ch, pipe, inst);
		}
	}
	
}
