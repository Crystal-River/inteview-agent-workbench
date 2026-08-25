package interview.guide.eval;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 灌入演示知识库：把内置的中文面试主题文本经真实分块 + embedding 写入 pgvector，
 * 供检索评测使用。复用 {@link KnowledgeBaseVectorService#vectorizeAndStore} 的完整链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.eval.enabled", havingValue = "true")
public class DemoKnowledgeBaseSeeder {

  private final KnowledgeBaseVectorService vectorService;
  private final EvalProperties evalProperties;

  public void seed() {
    log.info("开始灌入演示知识库: kbId={}, contentLength={}",
        evalProperties.getDemoKbId(), DEMO_CONTENT.length());
    vectorService.vectorizeAndStore(evalProperties.getDemoKbId(), DEMO_CONTENT);
    log.info("演示知识库灌入完成: kbId={}", evalProperties.getDemoKbId());
  }

  /** 内置中文面试主题文本，覆盖操作系统 / 网络 / 数据库 / Java 等领域。 */
  private static final String DEMO_CONTENT = """
      进程是操作系统进行资源分配和调度的基本单位，拥有独立的地址空间、文件描述符表、
      信号处理等资源。线程是进程内的执行单元，同一进程内的多个线程共享进程的地址空间、
      打开的文件、信号处理器等资源，但每个线程拥有自己独立的栈、程序计数器和寄存器集合。
      进程之间隔离性强，一个进程崩溃不会直接影响其他进程；而同一进程内的线程由于共享内存，
      一个线程的崩溃可能破坏共享数据并导致整个进程退出。进程切换需要切换页表和虚拟地址空间，
      开销较大；线程切换只需切换栈和寄存器，开销较小。在实际的并发编程中，创建线程的开销远小于
      创建进程，因此高并发服务通常采用线程池 + 多线程模型，而非频繁地创建和销毁进程。

      死锁是指两个或多个进程在运行过程中，因争夺资源而造成的一种互相等待的僵局，
      若无外力干预，这些进程都将无法向前推进。产生死锁必须同时满足四个必要条件：互斥条件，
      即资源一次只能被一个进程占用；请求与保持条件，即进程已经持有了至少一个资源，
      又提出新的资源请求，但该资源已被其他进程占用，此时请求进程被阻塞，但对自己已获得的资源保持不放；
      不可剥夺条件，即进程已获得的资源在未使用完之前不能被强行剥夺，只能在使用完后由自己释放；
      循环等待条件，即存在一个进程与资源的循环等待链。预防死锁的思路是破坏上述四个条件之一，
      例如通过一次性申请所有资源来破坏请求与保持条件，通过资源有序分配法来破坏循环等待条件。
      银行家算法是一种死锁避免方法，通过判断系统是否处于安全状态来决定是否分配资源。

      TCP 建立连接采用三次握手。第一次握手，客户端向服务器发送 SYN 报文，并进入 SYN_SENT 状态，
      随机生成一个初始序列号 seq=x。第二次握手，服务器收到 SYN 后，回复 SYN+ACK 报文，
      确认号 ack=x+1，同时生成自己的初始序列号 seq=y，进入 SYN_RCVD 状态。第三次握手，
      客户端收到 SYN+ACK 后，回复 ACK 报文，确认号 ack=y+1，双方进入 ESTABLISHED 状态。
      三次握手的目的是防止历史连接请求突然传到服务器，并同步双方的初始序列号，
      确保双方都具备收发能力。TCP 断开连接采用四次挥手，因为 TCP 是全双工通信，
      每个方向都需要独立关闭。第一次挥手，主动关闭方发送 FIN 报文；第二次挥手，
      被动方回复 ACK，表示收到关闭请求，但此时被动方可能还有数据要发送；第三次挥手，
      被动方发送 FIN 报文；第四次挥手，主动方回复 ACK。主动关闭方在发送最后一个 ACK 后
      会进入 TIME_WAIT 状态，等待 2 倍的最大报文段生存时间（2MSL），以确保最后的 ACK 能够到达。

      MySQL 的 InnoDB 存储引擎默认使用 B+ 树作为索引结构。B+ 树的所有数据都存储在叶子节点，
      非叶子节点只存储索引键值，因此树的高度较低，磁盘 I/O 次数少，适合范围查询和排序。
      主键索引（聚簇索引）的叶子节点直接存储整行数据；二级索引（非聚簇索引）的叶子节点存储的是
      主键值，因此通过二级索引查询需要先查到主键，再回表查询整行数据，这就是回表。
      覆盖索引是指查询的列都能在索引中找到，无需回表，可以提高查询性能。索引设计应遵循最左前缀原则，
      即联合索引的查询条件要尽可能从最左边的列开始。事务的四大特性是原子性、一致性、隔离性和持久性。
      InnoDB 通过 redo log 保证持久性，通过 undo log 保证原子性和实现多版本并发控制（MVCC）。
      事务隔离级别从低到高依次为：读未提交、读已提交、可重复读和串行化。MySQL 默认的隔离级别是
      可重复读，通过 MVCC 和间隙锁在一定程度上避免了幻读问题。

      Java 虚拟机（JVM）的内存区域主要分为堆、方法区、虚拟机栈、本地方法栈和程序计数器。
      其中堆和方法区是线程共享的，虚拟机栈、本地方法栈和程序计数器是线程私有的。堆用于存放对象实例，
      是垃圾回收的主要区域，通常进一步划分为新生代和老年代，新生代又分为 Eden 区和两个 Survivor 区。
      垃圾回收的基本思路是标记出不再被引用的对象并回收其内存。常见的垃圾回收算法有标记-清除、
      标记-复制和标记-整理。标记-清除算法会产生内存碎片；标记-复制算法将存活对象复制到另一块区域，
      适用于新生代；标记-整理算法在标记后将存活对象向一端移动，适用于老年代。
      判断对象是否存活通常使用可达性分析算法，以一组称为 GC Roots 的对象为起点，
      从这些节点向下搜索，无法到达的对象即为可回收对象。常见的垃圾收集器包括 Serial、Parallel、
      CMS 和 G1，其中 G1 采用 Region 分区的设计，兼顾吞吐量和停顿时间。
      """;
}
