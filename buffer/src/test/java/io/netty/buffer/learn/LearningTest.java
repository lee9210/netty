package io.netty.buffer.learn;

import io.netty.util.Recycler;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class LearningTest {
    static class WrapRecycler {

        private List<String> list;

        private final static Recycler<WrapRecycler> RECYCLER = new Recycler<WrapRecycler>() {
            @Override
            protected WrapRecycler newObject(Handle<WrapRecycler> handle) {
                return new WrapRecycler(handle);
            }
        };

        Recycler.Handle<WrapRecycler> handle;

        WrapRecycler(Recycler.Handle<WrapRecycler> handle) {
            this.handle = handle;
            this.list = new ArrayList<String>(1000);
        }

        List<String> getList() {
            return list;
        }

        static WrapRecycler getInstance() {
            return RECYCLER.get();
        }

        void recycle() {
            handle.recycle(this);
        }
    }

    @Test
    public void testDifferentThreadRecycle() throws InterruptedException, IOException {
        System.out.println("Main thread started ...");
        final WrapRecycler instance = WrapRecycler.getInstance();
        instance.getList().add("111");      // main 线程放入一个字符串
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        new Thread(new Runnable() {         // 这里新创建一个线程,在新的线程中可以回收main线程中的对象.
            @Override
            public void run() {
                System.out.println("Sub thread started ...");

                List<String> list = instance.getList();
                list.add("222");            // 子线程放入一个字符串.

                instance.recycle();         // 对main线程从对象池中回去的对象家进行回收动作.

                System.out.println("Sub Thread get list : " + WrapRecycler.getInstance().getList());    // 在子线程中从对象池获取对象
                countDownLatch.countDown();
            }
        }).start();

        countDownLatch.await();

        System.out.println("Main Thread get list : " + WrapRecycler.getInstance().getList());           // 在主线程中从对象池获取对象
    }

    private static final Recycler<User> RECYCLER = new Recycler<User>() {
        @Override
        protected User newObject(Handle<User> handle) {
            return new User(handle);
        }
    };

    static class User{
        private final Recycler.Handle<User> handle;
        public User(Recycler.Handle<User> handle){
            this.handle=handle;
        }
        public void recycle(){
            handle.recycle(this);
        }
    }
    public static void main(String[] args){
        User user1 = RECYCLER.get();
        user1.recycle();
        User user2 = RECYCLER.get();
        user2.recycle();
        System.out.println(user1==user2);
    }

    private static final Recycler<User1> userRecycler = new Recycler<User1>() {
        @Override
        protected User1 newObject(Handle<User1> handle) {
            return new User1(handle);
        }
    };

    static final class User1 {
        private String name;
        private Recycler.Handle<User1> handle;

        public void setName(String name){
            this.name = name;
        }

        public User1(Recycler.Handle<User1> handle) {
            this.handle = handle;
        }

        public void recycle() {
            handle.recycle(this);
        }
        public String toString(){
            return null == name ? "init" : name;
        }
    }
    @Test
    public void testGetAndRecycleAtSameThread() {
        // 1、从回收池获取对象
        User1 user1 = userRecycler.get();
        // 2、设置对象并使用
        user1.setName("hello,java");
        System.out.println(user1);
        // 3、对象恢复出厂设置
        user1.setName(null);
        // 4、回收对象到对象池
        user1.recycle();
        // 5、从回收池获取对象
        User1 user2 = userRecycler.get();
        Assert.assertSame(user1, user2);
    }
    @Test
    public void testGetAndRecycleAtDifferentThread() throws InterruptedException {
        // 1、从回收池获取对象
        User1 user1 = userRecycler.get();
        // 2、设置对象并使用
        user1.setName("hello,java");

        new Thread(()->{
            System.out.println(user1);
            // 3、对象恢复出厂设置
            user1.setName(null);
            // 4、回收对象到对象池
            user1.recycle();
        }).start();

        // 5、从回收池获取对象
        User1 user2 = userRecycler.get();
        System.out.println(user2);
        Assert.assertSame(user1, user2);
    }
}
