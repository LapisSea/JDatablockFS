package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthTryTests{
	@Test
	public void mergesValuesAndIsolatesScopes() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .arg(int.class, "mode")
			  .returns(String.class)
			  .body()
			  .tryRun(b -> {
				  b.val(1).setIntoNewVar("local");
				  b.get("mode").val(1).ifEquality(c -> c.throwNew(IllegalArgumentException.class, "bad"));
				  b.get("mode").val(2).ifEquality(c -> c.throwNew(IOException.class, "io"));
				  b.val("success");
			  })
			  .tryCatch(IllegalArgumentException.class, "error", b -> {
				  b.val(2L).setIntoNewVar("local");
				  b.get("error").call("getMessage");
			  })
			  .tryCatch(IOException.class, "error", b -> b.throwNew(RuntimeException.class, a -> a.get("error")))
			  .returnOp();
		});
		var run = cls.getMethod("run", int.class);
		assertThat(run.invoke(null, 0)).isEqualTo("success");
		assertThat(run.invoke(null, 1)).isEqualTo("bad");
		assertThatThrownBy(() -> run.invoke(null, 2)).hasCauseInstanceOf(RuntimeException.class)
		                                             .rootCause().isInstanceOf(IOException.class);
	}
	
	@Test
	public void returnsAndUnhandledThrows() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .arg(int.class, "mode")
			  .returns(long.class)
			  .body()
			  .tryRun(b -> {
				  b.get("mode").val(1).ifEquality(c -> c.throwNew(IOException.class));
				  b.get("mode").val(2).ifEquality(c -> c.throwNew(IllegalStateException.class));
				  b.val("discard").val(42L).returnOp();
			  })
			  .tryCatch(IOException.class, b -> b.val(24L).returnOp());
		});
		var run = cls.getMethod("run", int.class);
		assertThat(run.invoke(null, 0)).isEqualTo(42L);
		assertThat(run.invoke(null, 1)).isEqualTo(24L);
		assertThatThrownBy(() -> run.invoke(null, 2)).hasCauseInstanceOf(IllegalStateException.class);
	}
	
	@Test
	public void nestedHandlersTakePrecedenceAndCatchFailuresEscape() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .arg(boolean.class, "rethrow")
			  .returns(int.class)
			  .body()
			  .tryRun(outer -> {
				  outer.tryRun(b -> {
					       b.throwNew(IOException.class);
				       })
				       .tryCatch(IOException.class, b -> {
					       b.get("rethrow").ifTrue(c -> c.throwNew(IllegalStateException.class));
					       b.val(1);
				       })
				       .tryCatch(IllegalStateException.class, b -> b.val(99));
			  })
			  .tryCatch(Exception.class, b -> b.val(2));
		});
		var run = cls.getMethod("run", boolean.class);
		assertThat(run.invoke(null, false)).isEqualTo(1);
		assertThat(run.invoke(null, true)).isEqualTo(2);
	}
	
	@Test(timeOut = 10000)
	public void loopExitsFromTryAndCatch() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .arg(boolean.class, "catchBreak")
			  .returns(int.class)
			  .body()
			  .val(0).setIntoNewVar("i")
			  .loop(c -> c.val(true), b -> b.tryRun(t -> {
				  t.inc("i");
				  t.get("i").val(1).ifEquality(CodeBlock::continueOp);
				  t.get("i").val(2).ifEquality(c -> c.throwNew(IOException.class));
				  t.get("catchBreak").ifTrue(c -> c.throwNew(IOException.class));
				  t.breakOp();
			  }).tryCatch(IOException.class, c -> {
				  c.get("i").val(2).ifEquality(CodeBlock::continueOp);
				  c.breakOp();
			  }))
			  .get("i").returnOp();
		});
		var run = cls.getMethod("run", boolean.class);
		assertThat(run.invoke(null, false)).isEqualTo(3);
		assertThat(run.invoke(null, true)).isEqualTo(3);
	}
	
	@Test
	public void emptyTryAndCatchAreValid() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc().body().tryRun(b -> { }).tryCatch(IOException.class, b -> { });
		});
		cls.getMethod("run").invoke(null);
	}
	
	@Test
	public void mergedResultsCanInitializeLocalsAndFeedOperations() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			
			cd.function("run").staticAcc()
			  .returns(StringBuilder.class)
			  .body()
			  .tryRun(b -> b.throwNew(IOException.class))
			  .tryCatch(IOException.class, b -> b.val("caught"))
			  .setIntoNewVar("result")
			  .newObj(StringBuilder.class, args -> args.get("result"))
			  .returnOp();
			
			cd.function("mask").staticAcc()
			  .returns(long.class)
			  .body()
			  .tryRun(b -> b.val(15L))
			  .tryCatch(IOException.class, b -> b.val(0L))
			  .bitAnd(3L)
			  .returnOp();
		});
		assertThat(cls.getMethod("run").invoke(null).toString()).isEqualTo("caught");
		assertThat(cls.getMethod("mask").invoke(null)).isEqualTo(3L);
	}
	
	@Test
	public void mergesWideAndReferenceResults() throws Exception{
		var cls = TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .arg(boolean.class, "fail")
			  .arg(List.class, "trace")
			  .returns(long.class)
			  .body()
			  .tryRun(b -> {
				  b.get("fail").ifTrue(c -> c.throwNew(IOException.class));
				  b.val(42L).val("success");
			  })
			  .tryCatch(IOException.class, b -> b.val(24L).val("caught"))
			  .setIntoNewVar("message")
			  .setIntoNewVar("number")
			  .get("trace").call("add", args -> args.get("message")).pop()
			  .get("number").returnOp();
		});
		var run    = cls.getMethod("run", boolean.class, List.class);
		var events = new ArrayList<String>();
		assertThat(run.invoke(null, false, events)).isEqualTo(42L);
		assertThat(run.invoke(null, true, events)).isEqualTo(24L);
		assertThat(events).containsExactly("success", "caught");
	}
	
	@Test
	public void rejectsInvalidClauseAndScopeUsage(){
		for(CodeArg invalid : new CodeArg[]{
			b -> b.tryRun(c -> { }),
			b -> b.tryRun(CodeBlock::returnOp),
			b -> b.tryCatch(IOException.class, c -> { }),
			b -> b.tryRun(c -> { }).tryCatch(Exception.class, c -> { }).tryCatch(IOException.class, c -> { }),
			b -> b.tryRun(c -> { }).tryCatch(IOException.class, c -> { }).val(1).pop().tryCatch(Exception.class, c -> { }),
			b -> b.tryRun(c -> c.val(1).setIntoNewVar("secret")).tryCatch(IOException.class, c -> c.get("secret")),
			b -> b.tryRun(c -> { }).tryCatch(IOException.class, "error", c -> { }).get("error")
		}){
			assertThatThrownBy(() -> TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
				invalid.accept(cd.function("run").staticAcc().body());
			})).isInstanceOf(MalformedJorth.class);
		}
	}
	
	@Test
	public void rejectsIncomingOperands(){
		for(CodeArg invalid : new CodeArg[]{
			b -> b.val(1).tryRun(c -> { }),
			b -> b.val(2L).scope(c -> c.tryRun(t -> { })),
			b -> b.val(3D).val(true).ifTrue(c -> c.tryRun(t -> { })),
			b -> b.val(4L).loop(c -> c.val(true), c -> c.tryRun(t -> { })),
			b -> b.newObj(StringBuilder.class, args -> args.tryRun(c -> { })),
			b -> b.tryRun(c -> c.val(5)).tryCatch(IOException.class, c -> c.val(6)).tryRun(c -> { })
		}){
			assertThatThrownBy(() -> TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
				invalid.accept(cd.function("run").staticAcc().body());
			})).isInstanceOf(MalformedJorth.class)
			   .hasMessageContaining("tryRun requires an empty operand stack");
		}
	}
	
	@Test
	public void rejectsMismatchingStacks(){
		assertThatThrownBy(() -> TestUtils.generateAndLoadInstanceSimple(TestUtils.autoName(), cd -> {
			cd.function("run").staticAcc()
			  .returns(int.class)
			  .body()
			  .tryRun(b -> b.val(1))
			  .tryCatch(IOException.class, b -> b.val(2L))
			  .returnOp();
		})).isInstanceOf(MalformedJorth.class);
	}
}
