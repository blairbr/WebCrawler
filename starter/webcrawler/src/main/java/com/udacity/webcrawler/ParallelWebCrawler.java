package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;

  private final PageParserFactory parserFactory;

  private final int maxDepth;

  private final List<Pattern> ignoredUrls;

  @Inject
  ParallelWebCrawler(
      Clock clock,
      PageParserFactory parserFactory,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount,
      @MaxDepth int maxDepth,
      @IgnoredUrls List<Pattern> ignoredUrls
  )
  {
    this.clock = clock;
    this.parserFactory = parserFactory;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
  }

  public class SearchAction extends RecursiveAction {
    private final String url;
    private final Clock clock;
    private final Instant deadline;
    private final int maxDepth;
    private final PageParserFactory parserFactory;
    private final List<Pattern> ignoredUrls;
    private final Map<String, Integer> wordCounts;
    private final Set<String> visitedUrls;

    public SearchAction(String url, Clock clock, Instant deadline, int maxDepth, PageParserFactory parserFactory, List<Pattern> ignoredUrls, Map<String, Integer> wordCounts, Set<String> visitedUrls)
    {
      this.url = url;
      this.clock = clock;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.parserFactory = parserFactory;
      this.ignoredUrls = ignoredUrls;
      this.wordCounts = wordCounts;
      this.visitedUrls = visitedUrls;
    }
    @Override
    protected void compute() {
      if (clock.instant().isBefore(deadline) && maxDepth > 0) {
        List<String> sublinks = getSublinks(url);
        List<SearchAction> searchActionList = createSearchActionList(sublinks);
        ForkJoinTask.invokeAll(searchActionList);
      }
    }

    private List<String> getSublinks(String url) {
      List<String> sublinks = new ArrayList<>();
      if (clock.instant().isAfter(deadline) || maxDepth == 0) {
        return sublinks;
      }
      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return sublinks;
        }
      }
      //thread safe
      if (!visitedUrls.add(url)) {
        return sublinks;
      }
      visitedUrls.add(url);

      PageParser.Result result = parserFactory.get(url).parse();
      for (Map.Entry<String, Integer> entry : result.getWordCounts().entrySet()) {
        if (wordCounts.containsKey(entry.getKey())) {
          wordCounts.put(entry.getKey(), entry.getValue() + wordCounts.get(entry.getKey()));
        }
        else {
          wordCounts.put(entry.getKey(), entry.getValue());
        }
      }
      sublinks.addAll(result.getLinks());
      return sublinks;
    }

    private List<SearchAction> createSearchActionList(List<String> sublinks) {
      List<SearchAction> searchActionList = new ArrayList<>();
      for (String suburl : sublinks) {
        SearchAction searchAction = new SearchAction(
                suburl, clock, deadline, maxDepth -1, parserFactory, ignoredUrls, wordCounts, visitedUrls);
        searchActionList.add(searchAction);
      }
      return searchActionList;
    }
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    Map<String, Integer> wordCounts = Collections.synchronizedMap(new HashMap<>());
    Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
    invokeSearchAction(startingUrls, deadline, wordCounts, visitedUrls);
    return getCrawlResult(wordCounts, visitedUrls);
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }

  private void invokeSearchAction(List<String> startingUrls, Instant deadline, Map<String, Integer> wordCounts, Set<String> visitedUrls) {
    for (String url : startingUrls) {
      SearchAction searchAction = new SearchAction(
              url, clock, deadline, maxDepth, parserFactory, ignoredUrls, wordCounts, visitedUrls
      );
      pool.invoke(searchAction);
    }
  }
  private CrawlResult getCrawlResult(Map<String, Integer> wordCounts, Set<String> visitedUrls) {
    if(wordCounts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(wordCounts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }
    else {
      return new CrawlResult.Builder()
              .setWordCounts(WordCounts.sort(wordCounts, popularWordCount))
              .setUrlsVisited(visitedUrls.size())
              .build();
    }
  }
}
