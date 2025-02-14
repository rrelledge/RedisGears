package gears;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sun.management.HotSpotDiagnosticMXBean;

import gears.operations.AccumulateByOperation;
import gears.operations.AccumulateOperation;
import gears.operations.AsyncFilterOperation;
import gears.operations.AsyncForeachOperation;
import gears.operations.AsyncMapOperation;
import gears.operations.ExtractorOperation;
import gears.operations.FilterOperation;
import gears.operations.FlatMapOperation;
import gears.operations.ForeachOperation;
import gears.operations.MapOperation;
import gears.operations.OnRegisteredOperation;
import gears.operations.OnUnregisteredOperation;
import gears.operations.ValueInitializerOperation;
import gears.readers.BaseReader;

/**
 * A RedisGears pipe builder. The data pass in the pipe
 * and transforms according to the operations in the pipe.
 * 
 * Each pipe starts with a reader, the reader is responsible of supply
 * data to the rest of the operation. 
 * To create a builder use the following example:
 * 
 * 	   BaseReader reader = ...; // initialize reader
 *     GearsBuilder builder = GearsBuilder.CreateGearsBuilder(reader)
 *
 * @param T - The current record type pass in the pipe 
 */
public class GearsBuilder<T extends Serializable>{
	private BaseReader<T> reader;
	private long ptr;
	
	/**
	 * Internal use
	 * 
	 * Notify that a class loader has freed so we will free native structs
	 * holding the class loader
	 * @param prt - pointer to native data
	 */
	protected static native void classLoaderFinalized(long prt);
	
	/**
	 * Internal use
	 * 
	 * Internal use, called on GearsBuilder to creation to initialize
	 * native data
	 * 
	 * @param reader - the reader name which the builder was created with
	 * @param desc - execution description (could be null)
	 */
	private native void init(String reader, String desc);
	
	/**
	 * Internal use
	 * 
	 * Called when builder is freed to free native data.
	 */
	private native void destroy();
	
	/**
	 * Add a map operation to the pipe.
	 * Example (map each record to the record value):
	 * <pre>{@code
	 * 		GearsBuilder.CreateGearsBuilder(reader).
	 * 		map(r->{
     *    		return r.getStringVal();
	 *   	})
	 * }</pre>
	 * 
	 * @param <I> The template type of the returned builder
	 * @param mapper - the map operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native <I extends Serializable> GearsBuilder<I> map(MapOperation<T, I> mapper);
	
	@SuppressWarnings("unchecked")
	public <I extends Serializable> GearsBuilder<I> asyncMap(AsyncMapOperation<T, I> mapper){
		this.map(r->{
			GearsFuture<I> f = mapper.map(r);
			if(f == null) {
				throw new Exception("null future returned");
			}
			return new FutureRecord<I>(f);
		});
		
		return (GearsBuilder<I>) this;
	}
	
	/**
	 * Add a flatmap operation to the pipe. the operation must return an Iterable
	 * object. RedisGears iterate over the element in the Iterable object and pass
	 * them one by one in the pipe.
	 * Example:
	 * <pre>{@code 		
	 * 		GearsBuilder.CreateGearsBuilder(reader).
	 *   	flatMap(r->{
	 *   		return r.getListVal();
	 *   	}); 
	 * }</pre>
	 * 
	 * @param <I> The template type of the returned builder
	 * @param faltmapper - the faltmap operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native <I extends Serializable> GearsBuilder<I> flatMap(FlatMapOperation<T, I> faltmapper);
	
	/**
	 * Add a foreach operation to the pipe.
	 * 
	 * Example:
	 * <pre>{@code 		
	 * 		GearsBuilder.CreateGearsBuilder(reader).
	 *  	foreach(r->{
	 *   		r.getSetVal("test");
	 * 		});
	 * }</pre>
	 * 
	 * @param foreach - the foreach operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native GearsBuilder<T> foreach(ForeachOperation<T> foreach);
	
	public GearsBuilder<T> asyncForeach(AsyncForeachOperation<T> foreach){
		this.foreach(r->{				
			GearsFuture<Serializable> f = foreach.foreach(r);
			if(f == null) {
				throw new Exception("null future returned");
			}
			new FutureRecord<Serializable>(f);
		});
		
		return this;
	}
	
	/**
	 * Add a filter operation to the pipe.
	 * The filter should return true if RedisGears should continue process the record
	 * and otherwise false
	 * 
	 * Example:
	 * <pre>{@code 
	 *  	GearsBuilder.CreateGearsBuilder(reader).
	 *   	filter(r->{
	 *   		return !r.getKey().equals("UnwantedKey");
	 *   	});
	 * }</pre>
	 * 
	 * @param foreach - the foreach operation
	 * @return - GearsBuilder with the same template type as the input builder, notice that the return object might be the same as the previous.
	 */
	public native GearsBuilder<T> filter(FilterOperation<T> filter);
	
	public GearsBuilder<T> asyncFilter(AsyncFilterOperation<T> filter){
		this.filter(r->{
			GearsFuture<Boolean> f = filter.filter(r);
			
			if(f == null) {
				throw new Exception("null future returned");
			}
			
			new FutureRecord<Boolean>(f);
			
			return true;
		});
		
		return this;
	}
	
	/**
	 * Add an accumulateBy operation to the pipe.
	 * the accumulate by take an extractor and an accumulator.
	 * The extractor extracts the data by which we should perform the group by
	 * The accumulate is the reduce function. The accumulator gets the group, accumulated data, and current record
	 * and returns a new accumulated data. The initial value of the accumulator is null.
	 * 
	 * Example (counting the number of unique values):
	 * <pre>{@code
	 * 		GearsBuilder.CreateGearsBuilder(reader).
     *   	accumulateBy(r->{
	 *   		return r.getStringVal();
	 *   	},(k, a, r)->{
	 *   		Integer ret = null;
	 *   		if(a == null) {
	 *   			ret = 0;
	 *   		}else {
	 *   			ret = (Integer)a;
	 *   		}
	 *   		return ret + 1;
	 *   	});
	 * }</pre>
	 * 
	 * @param <I> - The template type of the returned builder
	 * @param extractor - the extractor operation
	 * @param accumulator - the accumulator operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native <I extends Serializable> GearsBuilder<I> accumulateBy(ExtractorOperation<T> extractor, AccumulateByOperation<T, I> accumulator);
	
	/**
	 * A sugar syntax for the previous accumulateBy that gets a valueInitiator callback
	 * so there is no need to check if the accumulator is null.
	 * 
	 * Example (same example of counting the number of unique values):
	 * <pre>{@code 		
	 * 		GearsBuilder.CreateGearsBuilder(reader).
     *    	accumulateBy(()->{
	 *   		return 0;
	 *   	},r->{
	 *   		return r.getStringVal();
	 *   	},(k, a, r)->{
	 *   		return a + 1;
	 *   	});
	 * }</pre>
	 * 
	 * @param <I> - The template type of the returned builder 
	 * @param valueInitializer - an initiator operation, 
	 * whenever the accumulated values is null we will use this function to initialize it.
	 * @param extractor - the extractor operation
	 * @param accumulator - the accumulator operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public <I extends Serializable> GearsBuilder<I> accumulateBy(ValueInitializerOperation<I> valueInitializer, ExtractorOperation<T> extractor, AccumulateByOperation<T, I> accumulator){
		return this.accumulateBy(extractor, new AccumulateByOperation<T, I>() {
			
			private static final long serialVersionUID = 1L;

			@Override
			public I accumulateby(String k, I a, T r) throws Exception {
				if(a == null) {
					a = valueInitializer.getInitialValue();
				}
				return accumulator.accumulateby(k, a, r);
			}
			
		});
	}
	
	/**
	 * Same as accumulate by but performed locally on each shard (without moving
	 * data between shards).
	 * 
	 * Example:
	 * 
	 * <pre>{@code
	 * 		GearsBuilder.CreateGearsBuilder(reader).
	 *   	localAccumulateBy(r->{
	 *   		return r.getStringVal();
	 *   	},(k, a, r)->{
	 *   		Integer ret = null;
	 *   		if(a == null) {
	 *   			ret = 0;
	 *   		}else {
	 *   			ret = (Integer)a;
	 *   		}
	 *   		return ret + 1;
	 *   	});
	 * }</pre>
	 * 
	 * @param <I> - The template type of the returned builder
	 * @param extractor - the extractor operation
	 * @param accumulator - the accumulator operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native <I extends Serializable> GearsBuilder<I> localAccumulateBy(ExtractorOperation<T> extractor, AccumulateByOperation<T, I> accumulator);
	
	/**
	 * A many to one mapped, reduce the record in the pipe to a single record.
	 * The initial accumulator object is null (same as for accumulateBy)
	 * 
	 * Example (counting the number of records):
	 * <pre>{@code		
	 * 		GearsBuilder.CreateGearsBuilder(reader).
     *    	accumulate((a, r)->{
	 *   		Integer ret = null;
	 *   		if(a == null) {
	 *   			ret = 1;
	 *   		}else {
	 *   			ret = (Integer)a;
	 *   		}
	 *   		return ret + 1;
	 *   	});
	 * }</pre>
	 * 
	 * @param <I> - The template type of the returned builder
	 * @param accumulator - the accumulate operation
	 * @return - GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native <I extends Serializable> GearsBuilder<I> accumulate(AccumulateOperation<T, I> accumulator);
	
	/**
	 * A sugar syntax for the previous accumulateBy that gets the initial value as parameter
	 * so there is no need to check if the accumulated object is null.
	 * 
	 * Example (counting the number of records):
	 * <pre>{@code
	 *  	GearsBuilder.CreateGearsBuilder(reader).
	 *   	accumulate(0, (a, r)->{
	 *   		return a + 1;
	 *   	});
	 * }</pre>
	 * 
	 * @param <I> - The template type of the returned builder
	 * @param initialValue - the initial value of the accumulated object
	 * @param accumulator - the accumulate operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public <I extends Serializable> GearsBuilder<I> accumulate(I initialValue, AccumulateOperation<T, I> accumulator){
		return this.accumulate((a,r)->{
			if(a == null) {
				a = initialValue;
			}
			
			return accumulator.accumulate(a, r);
		});
	}
	
	/**
	 * Collects all the records to the shard that started the execution.
	 * @return GearsBuilder with the same template type as the input builder, notice that the return object might be the same as the previous.
	 */
	public native GearsBuilder<T> collect();
	
	/**
	 * Add a count operation to the pipe, the operation returns a single record
	 * which is the number of records in the pipe.
	 * @return GearsBuilder with a new template type (Integer), notice that the return object might be the same as the previous.
	 */
	public GearsBuilder<Integer> count(){
		return this.accumulate(0, (a, r)-> 1 + a);
	}
	
	/**
	 * Returns a String that maps to the current shard according to the cluster slot mapping.
	 * It is very useful when there is a need to create a key and make sure that the key
	 * reside on the current shard.
	 * 
	 *  Example:
	 *  	execute("set", String.format("key{%s}", GearsBuilder.hashtag()), "1")
	 *  
	 *  In the following example the "{}" make sure the hslot will be calculated on the String located between the
	 *  "{}" and using the  hashtag function we know that this String will be mapped to the current shard.
	 *  
	 * @return String reside on the current shard when calculating hslot
	 */
	public static native String hashtag();
	
	/**
	 * Return a configuration value of a given key. the configuration value 
	 * can be set when loading the module or using RG.CONFIGSET command (https://oss.redislabs.com/redisgears/commands.html#rgconfigset)
	 * 
	 * @param key - the configuration key for which to return the value
	 * @return the configuration value of the given key
	 */
	public static native String configGet(String key);

	/**
	 * Execute a command on Redis
	 * 
	 * @param command - the command the execute
	 * @return the command result (could be a simple String or an array or Strings depends on the command)
	 */
	public static native Object executeArray(String[] command);
	
	/**
	 * On command overriding, call the next execution that override the command or
	 * the original command itself.
	 * 
	 * @param args - the command args to use
	 * @return the command result (could be a simple String or an array or Strings depends on the command)
	 */
	public static native Object callNextArray(String[] args);
	
	/**
	 * On keys reader, if commands options was used, it is possible to get the
	 * command associated with the notification. The command is an array of 
	 * byte[] (just in case a blob was sent and its not a valid string).
	 * @return - the command associated with the notification
	 */
	public static native byte[][] getCommand();
	
	/**
	 * On keys reader, if commands options was used, it is possible to override
	 * the reply of the command associated with the notification.
	 * In case override the reply is not possible, an exception will be raised 
	 * @param reply - new reply
	 */
	public static native void overrideReply(Object reply);
	
	/**
	 * Returns the current memory ratio as value between 0-1.
	 * If the return value greater than 1 -> memory limit reached.
	 * If return value equal 0 -> no memory limit
	 */
	public static native float getMemoryRatio();
	
	/**
	 * Write a log message to the redis log file
	 * 
	 * @param msg - the message to write
	 * @param level - the log level
	 */
	public static native void log(String msg, LogLevel level);
	
	/**
	 * Whether or not to avoid keys notification.
	 * Return the old value 
	 * Usage Example :
	 * <pre>{@code
	 *  	boolean oldVal = GearsBuilder.setAvoidNotifications(true);
	 *      ...
	 *      GearsBuilder.setAvoidNotifications(oldVal);
	 * }</pre>
	 * @param val - true: notifications disabled, false: notifications enabled 
	 * @return - the old value
	 */
	public static native boolean setAvoidNotifications(boolean val);
	
	/**
	 * acquire the Redis global lock
	 */
	public static native void acquireRedisGil();
	
	/**
	 * release the Redis global lock
	 */
	public static native void releaseRedisGil();

	/**
	 * Internal use for performance increasment.
	 * 
	 * @param ctx
	 */
	public static native void jniTestHelper(long ctx);
	
	/**
	 * Write a log message with log level Notice to Redis log file
	 * @param msg - the message to write
	 */
	public static void log(String msg) {
		log(msg, LogLevel.NOTICE);
	}
	
	/**
	 * Execute a command on Redis
	 * 
	 * @param command - the command to execute
	 * @return the command result (could be a simple String or an array or Strings depends on the command)
	 */
	public static Object execute(String... command) {
		return executeArray(command);
	}
	
	/**
	 * On command overriding, call the next execution that override the command or
	 * the original command itself.
	 * 
	 * @param args - the arguments to use
	 * @return the command result (could be a simple String or an array or Strings depends on the command)
	 */
	public static Object callNext(String... args) {
		return callNextArray(args);
	}
	
	/**
	 * Add a repartition operation to the operation pipe.
	 * The repartition moves the records between the shards according to
	 * the extracted data.
	 * 
	 * Example (repartition by value):
	 * <pre>{@code 
	 * 		GearsBuilder.CreateGearsBuilder(reader).
     *   	repartition(r->{
	 *   		return r.getStringVal();
	 *   	});
	 * }</pre>
	 * 
	 * @param extractor - the extractor operation
	 * @return GearsBuilder with a new template type, notice that the return object might be the same as the previous.
	 */
	public native GearsBuilder<T> repartition(ExtractorOperation<T> extractor);

	/**
	 * Internal use, called by run function to start the native code
	 * @param reader - the reader object
	 */
	private native void innerRun(BaseReader<T> reader);
	
	/**
	 * Internal use, called by the register function to start the native code.
	 * 
	 * @param reader - the reader object
	 * @param mode - the registration mode
	 * @param onRegister - onRegister callback
	 * @param onUnregistered - onUnregister callback
	 * 
	 * @return - registration id
	 */
	private native String innerRegister(BaseReader<T> reader, ExecutionMode mode, OnRegisteredOperation onRegister, OnUnregisteredOperation onUnregistered);

	/**
	 * 
	 * @param sessionId
	 * @return
	 */
	public static native String getUpgradeData();
	
	/**
	 * Runs the current built pipe
	 * 
	 * @param jsonSerialize - indicate whether or no to serialize the results to json before returning them
	 * @param collect - indicate whether or not to collect the results from all the cluster before returning them
	 */
	public void run(boolean jsonSerialize, boolean collect) {
		if(jsonSerialize) {
			this.map(r->{
				ObjectMapper objectMapper = new ObjectMapper();
				String res = objectMapper.writeValueAsString(r);
				objectMapper.getTypeFactory().clearCache();
			        TypeFactory.defaultInstance().clearCache();
                 	        return res;
			});
		}
		if(collect) {
			this.collect();
		}
		innerRun(reader);
	}

	/*
	 * Runs the current built pipe, collects all the results from all the shards
	 * before returning them and serialize them as a json object.
	 */
	public void run() {
		run(true, true);
	}
	
	/*
	 * Register the pipe as an async registration (i.e execution will run asynchronously on the entire cluster)
	 * 
	 * @return - registration id
	 */
	public String register() {
		return register(ExecutionMode.ASYNC, null, null);
	}
	
	/**
	 * Register the pipe to be trigger on events
	 * @param mode - the execution mode to use (ASYNC/ASYNC_LOCAL/SYNC)
	 * 
	 * @return - registration id
	 */
	public String register(ExecutionMode mode) {
		return register(mode, null, null);
	}
	
	/**
	 * Register the pipe to be trigger on events
	 * @param mode - the execution mode to use (ASYNC/ASYNC_LOCAL/SYNC)
	 * @param onRegister - register callback that will be called on each shard upon register
	 * @param onUnregistered - unregister callback that will be called on each shard upon unregister
	 * 
	 * @return - registration id
	 */
	public String register(ExecutionMode mode, OnRegisteredOperation onRegister, OnUnregisteredOperation onUnregistered) {
		return innerRegister(reader, mode, onRegister, onUnregistered);
	}
	
	/**
	 * Creates a new GearsBuilde object
	 * @param reader - the reader to use to create the builder
	 * @param desc - the execution description
	 */
	public GearsBuilder(BaseReader<T> reader, String desc) {
		if(reader == null) {
			throw new NullPointerException("Reader can not be null");
		}
		this.reader = reader;
		init(reader.getName(), desc);
	}
	
	/**
	 * Creates a new GearsBuilde object
	 * @param reader - the reader to use to create the builder
	 */
	public GearsBuilder(BaseReader<T> reader) {
		this(reader, null);
	}
	
	/**
	 * A static function to create GearsBuilder. We use this to avoid type warnings.
	 * @param <I> - The template type of the returned builder, this type is defined by the reader.
	 * @param reader - The pipe reader
	 * @param desc - the execution description
	 * @return a new GearsBuilder
	 */
	public static <I extends Serializable> GearsBuilder<I> CreateGearsBuilder(BaseReader<I> reader, String desc) {
		return new GearsBuilder<>(reader, desc);
	}
	
	/**
	 * A static function to create GearsBuilder. We use this to avoid type warnings.
	 * @param <I> - The template type of the returned builder, this type is defined by the reader.
	 * @param reader - The pipe reader
	 * @return a new GearsBuilder
	 */
	public static <I extends Serializable> GearsBuilder<I> CreateGearsBuilder(BaseReader<I> reader) {
		return new GearsBuilder<>(reader);
	}
	
	/**
	 * Internal use, set the ContextClassLoader each time we start running an execution
	 * @param cl - class loader
	 * @throws IOException
	 */
	private static void onUnpaused(ClassLoader cl) {
		Thread.currentThread().setContextClassLoader(cl);
	}
	
	/**
	 * Internal use, clean the ContextClassLoader when we finish to run an execution
	 * @throws IOException
	 */
	private static void cleanCtxClassLoader() throws IOException {
		Thread.currentThread().setContextClassLoader(null);
	}
	
	/**
	 * Internal use, serialize an object.
	 * @param o
	 * @param out
	 * @param reset
	 * @return
	 * @throws IOException
	 */
	private static byte[] serializeObject(Object o, GearsObjectOutputStream out, boolean reset) throws IOException {
		if(reset) {
			out.reset();
		}
		
		return out.serializeObject(o);
	}
	
	/**
	 * Internal user, deserialize an object
	 * @param bytes
	 * @param in
	 * @param reset
	 * @return
	 * @throws IOException
	 * @throws ClassNotFoundException
	 */
	private static Object deserializeObject(byte[] bytes, GearsObjectInputStream in, boolean reset) throws IOException, ClassNotFoundException {
		in.addData(bytes);
		return in.readObject();
	}
	
	/**
	 * Internal use, return stack trace of an execution as String.
	 * @param e
	 * @return
	 */
	private static String getStackTrace(Throwable e) {
		StringWriter writer = new StringWriter();
		e.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}
	
	/**
	 * Internal use, for performance boosting
	 * @param ctx
	 */
	private static void jniCallHelper(long ctx){
		jniTestHelper(ctx);
	}
	
	/**
	 * Internal use, return String representation of a record.
	 * @param record
	 * @return
	 */
	private static String recordToString(Serializable record){
		return record.toString();
	}
		
	@Override
	protected void finalize() throws Throwable {
		destroy();
	}
	
	/**
	 * Internal use, initiate a heap dump.
	 * @param dir
	 * @param filePath
	 * @throws IOException
	 */
	private static void dumpHeap(String dir, String filePath) throws IOException {
	    log("Dumping heap into: " + dir + '/' + filePath);
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
	    HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
	      server, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
	    mxBean.dumpHeap(dir + '/' + filePath, true);
	}
	
	/**
	 * Internal use, force running GC.
	 * @throws IOException
	 */
	private static void runGC() {
		System.gc();		
	}
	
	/**
	 * Internal use, information about the JVM.
	 * @throws JsonProcessingException 
	 * @throws IOException
	 */
	private static Object getStats(boolean strRep) throws JsonProcessingException {
		long totalAllocatedMemory = 0;
		
		List<Object> res = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        res.add("runtimeReport");
        List<Object> runtimeReport = new ArrayList<>();
        runtimeReport.add("heapTotalMemory");
        runtimeReport.add(runtime.totalMemory());
        runtimeReport.add("heapFreeMemory");
        runtimeReport.add(runtime.freeMemory());
        runtimeReport.add("heapMaxMemory");
        runtimeReport.add(runtime.maxMemory());
        res.add(runtimeReport);
        
        res.add("memoryMXBeanReport");
        MemoryUsage heapMemory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        List<Object> memoryMXBeanReport = new ArrayList<>();
        memoryMXBeanReport.add("heapMemory");
        memoryMXBeanReport.add(heapMemory);
        memoryMXBeanReport.add("nonHeapMemory");
        memoryMXBeanReport.add(nonHeapMemory);
        res.add(memoryMXBeanReport);
        
        totalAllocatedMemory += (heapMemory.getUsed() > heapMemory.getInit()) ? heapMemory.getUsed() : heapMemory.getInit();
        totalAllocatedMemory += (nonHeapMemory.getUsed() > nonHeapMemory.getInit()) ? nonHeapMemory.getUsed() : nonHeapMemory.getInit();
        
        res.add("pools");
        List<Object> pools = new ArrayList<>();
        
        List<MemoryPoolMXBean> memPool = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean p : memPool)
        {
        	MemoryUsage usage = p.getUsage();
        	pools.add(p.getName());
        	pools.add(usage);
        	totalAllocatedMemory += (usage.getUsed() > usage.getInit()) ? usage.getUsed() : usage.getInit();
        }
        
        res.add(pools);
        
        res.add("totalAllocatedMemory");
        res.add(totalAllocatedMemory);
        
        res.add("totalAllocatedMemoryHuman");
        res.add((totalAllocatedMemory / (1024.0 * 1024.0)) + "mb");
        
        if (strRep) {
        	ObjectMapper objectMapper = new ObjectMapper();
			String ret = objectMapper.writeValueAsString(res);
			objectMapper.getTypeFactory().clearCache();
		        TypeFactory.defaultInstance().clearCache();
                       return ret;
        } else {
        	return res;
        }		
	}
}
