package com.sdu.spark.rpc;

import com.sdu.spark.SecurityManager;
import com.sdu.spark.SparkException;
import com.sdu.spark.rpc.netty.NettyRpcEnvFactory;

import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.sdu.spark.utils.RpcUtils.lookupRpcTimeout;

/**
 *
 * @author hanhan.zhang
 * */
public abstract class RpcEnv {

    public SparkConf conf;
    private int defaultLookupTimeout;

    public RpcEnv(SparkConf conf) {
        this.conf = conf;

        this.defaultLookupTimeout = lookupRpcTimeout(conf);
    }

    // RpcEnv Server监听地址
    public abstract RpcAddress address();

    // RpcEndPoint节点引用
    public abstract RpcEndpointRef endPointRef(RpcEndpoint endPoint);

    // RpcEndPoint节点注册
    public abstract RpcEndpointRef setRpcEndPointRef(String name, RpcEndpoint endPoint);

    public RpcEndpointRef setRpcEndPointRef(String name, RpcAddress address) {
        RpcEndpointAddress endpointAddress = new RpcEndpointAddress(name, address);
        return setupEndpointRefByURI(endpointAddress.toString());
    }

    public abstract Future<RpcEndpointRef> asyncSetupEndpointRefByURI(String uri);

    public RpcEndpointRef setupEndpointRefByURI(String uri) {
        Future<RpcEndpointRef> f = asyncSetupEndpointRefByURI(uri);
        try {
            return f.get(defaultLookupTimeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new SparkException("register RpcEndpoint " + uri + " occur interrupt exception", e);
        } catch (ExecutionException e) {
            throw new SparkException("register RpcEndpoint " + uri + " occur execute exception", e);
        } catch (TimeoutException e) {
            throw new SparkException("register RpcEndpoint " + uri + " timeout", e);
        }
    }

    // RpcEndPoint关闭
    public abstract void stop(RpcEndpointRef endPoint);

    // RpcEnv关闭
    public abstract void awaitTermination();
    public abstract void shutdown();

    public abstract <T> T deserialize(DeserializeAction<T> deserializeAction);

    public abstract RpcEnvFileServer fileServer();

    public abstract ReadableByteChannel openChannel(String uri);

    public interface DeserializeAction<T> {
        T deserialize();
    }

    /********************************Spark RpcEnv*************************************/
    public static RpcEnv create(String name,
                         String host,
                         int port,
                         SparkConf conf,
                         SecurityManager securityManager,
                         boolean clientModel) {
        return create(
                name,
                host,
                host,
                port,
                conf,
                securityManager,
                0,
                clientModel);
    }

    public static RpcEnv create(String name,
                         String host,
                         int port,
                         SparkConf conf,
                         SecurityManager securityManager) {
       return create(name,
                     host,
                     host,
                     port,
                     conf,
                     securityManager,
                     0,
                     false);
    }

    public static RpcEnv create(String name,
                         String bindAddress,
                         String advertiseAddress,
                         int port,
                         SparkConf conf,
                         SecurityManager securityManager,
                         int numUsableCores,
                         boolean clientModel) {
        RpcEnvConfig rpcEnvConf = new RpcEnvConfig(conf,
                                                   name,
                                                   bindAddress,
                                                   advertiseAddress,
                                                   port,
                                                   securityManager,
                                                   numUsableCores,
                                                   clientModel
        );
        return new NettyRpcEnvFactory().create(rpcEnvConf);
    }
}
