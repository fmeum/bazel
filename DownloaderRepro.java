import java.net.URI;
import java.util.concurrent.Semaphore;

void main() throws Exception {
  var url = URI.create("https://example.com").toURL();
  var totalDownloads = 1000;
  var concurrentDownloads = 8;
  var semaphore = new Semaphore(concurrentDownloads);

  var futures = new ArrayList<Future<?>>(totalDownloads);
  var failures = new ConcurrentLinkedQueue<Throwable>();
  try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < totalDownloads; i++) {
      futures.add(
          executor.submit(() -> {
            semaphore.acquire();
            HttpURLConnection connection = null;
            try {
              connection = (HttpURLConnection) url.openConnection();
            } catch (Throwable e) {
              failures.add(e);
            } finally {
              if (connection != null) {
                connection.disconnect();
              }
              semaphore.release();
            }
            return null;
          }));
    }
  }
  for (var future : futures) {
    future.get();
  }
  if (!failures.isEmpty()) {
    System.out.println("Failures:");
    for (var failure : failures) {
      failure.printStackTrace();
    }
    System.exit(1);
  }
}
