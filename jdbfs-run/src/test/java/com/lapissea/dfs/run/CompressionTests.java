package com.lapissea.dfs.run;

import com.lapissea.dfs.chunk.AllocateTicket;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.jorth.Jorth;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;

import static com.lapissea.dfs.run.TestUtils.randomBatch;
import static org.testng.Assert.assertEquals;

public class CompressionTests{
	
	@DataProvider(name = "comps", parallel = true)
	Object[][] comps(){
		return Arrays.stream(IOCompression.Type.values()).map(t -> new Object[]{t}).toArray(Object[][]::new);
	}
	
	@Test(dataProvider = "comps")
	void directIntegrity(IOCompression.Type type){
		randomBatch(500, 100, (r, iter) -> {
			try{
				byte[] raw;
				if(iter == 0){
					raw = new byte[10];
					Arrays.fill(raw, (byte)(1));
				}else{
					raw = new byte[r.nextInt(1000)];
					for(int i = 0; i<raw.length; ){
						if(r.nextFloat()<0.2){
							for(int to = Math.min(i + r.nextInt(300) + 1, raw.length); i<to; i++){
								raw[i] = (byte)r.nextInt(256);
							}
						}else{
							var b = (byte)r.nextInt(256);
							for(int to = Math.min(i + r.nextInt(200) + 1, raw.length); i<to; i++){
								raw[i] = b;
							}
						}
					}
				}
				
				byte[] compressed   = type.pack(raw);
				byte[] uncompressed = type.unpack(compressed);
				
				assertEquals(uncompressed, raw, "Failed on " + iter);
			}catch(AssertionError e){
				throw e;
			}catch(Throwable e){
				throw new RuntimeException(iter + "", e);
			}
		});
	}
	
	
	@Test(dataProvider = "comps")
	<T extends IOInstance<T>> void typeIntegrity(IOCompression.Type type) throws Exception{
		
		var name = CompressionTests.class.getPackageName() + ".Holder$" + type;
		var bytecode = Jorth.generateClass(null, name, code -> {
			code.addImports(IOCompression.class, IOValue.class, IOInstance.Managed.class);
			code.write(
				"""
					extends #IOInstance.Managed
					public class {} start
						@ #IOCompression start value {!} end
						@ #IOValue
						public field data byte array
					end
					""", name, type);
		});
		
		//noinspection unchecked
		var clazz = (Class<T>)MethodHandles.lookup().defineClass(bytecode);
		
		var struct = Struct.of(clazz);
		var pipe   = StandardStructPipe.of(struct);
		
		var fuz = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.of(
			(rand, actionIndex, mark) -> {
				var instance = struct.emptyConstructor().make();
				
				var bytes = new byte[rand.nextInt(100)];
				rand.nextBytes(bytes);
				
				struct.getFields().requireExact(byte[].class, "data")
				      .set(null, instance, bytes);
				
				var data = com.lapissea.dfs.chunk.DataProvider.newVerySimpleProvider();
				var ch   = AllocateTicket.bytes(120).submit(data);
				
				pipe.write(ch, instance);
				var read = pipe.readNew(ch, null);
				
				assertEquals(instance, read);
			}
		), r -> null);
		
		fuz.runAndAssert(69, 500, 50);
	}
}
