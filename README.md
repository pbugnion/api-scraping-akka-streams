
# API scraping with Akka streams

This is the code for a [blog post](http://pascalbugnion.net/blog/scraping-apis-with-akka-streams.html) on building long-running, resilient API scrapers with Akka streams. 

We build a program that sends 250,000 requests to the Yelp API over several
days, gracefully handling unexpected behaviour, request limits and restarts. The
code is organised into seven sprints. In each sprint, we improve our
understanding of the behaviour of the API and improve the behaviour of our
stream.

## Requirements

You just need [SBT](http://www.scala-sbt.org) to run the code examples.

## Getting an API token

To run the examples in this blog post, you will need an OAuth token for the Yelp API. 

1. Register an [application](https://www.yelp.co.uk/developers/v3/manage_app) in the Yelp developer portal. Your mileage may vary, but I was able to put in generic responses in most of the fields. You will get a client ID and secret (sometimes called app ID and secret).

2. Use your client ID and secret to generate an oauth token. You need to send a POST request to `https://api.yelp.com/oauth2/token`. You can do this with `curl`, for instance:

```
$ curl -XPOST https://api.yelp.com/oauth2/token -d client_id=<your client id> -d client_secret=<your client secret> -d grant_type=client_credentials
```

This will return a JSON object containing the access token.

3. Save the token to an environment variable called `YELP_TOKEN`. Make sure that the variable is in scope when starting SBT:

```
$ YELP_TOKEN=<your token> sbt
[info] Loading global plugins from /home/sherlock/.sbt/0.13/plugins
[info] Set current project to apifetcher (in build file:/home/sherlock/workspace/api_blogpost/)
> 
```

4. You can now run any of the sprints:

```
> runMain Sprint4
[info] Running Sprint4
[info] 2017-02-26 11:30:54,933 INFO [Sprint4] - Found 249304 unique postcodes.
[info] 2017-02-26 11:30:59,154 INFO [Sprint4] - Found 70646 already processed.
[info] 2017-02-26 11:30:59,251 INFO [Sprint4] - There are 178658 still to do.
[info] 2017-02-26 11:32:49,519 INFO [Sprint4] - Processed 1000 restaurants
[info] 2017-02-26 11:34:41,984 INFO [Sprint4] - Processed 2000 restaurants
[info] 2017-02-26 11:36:40,863 INFO [Sprint4] - Processed 3000 restaurants
[info] Written 2542957 bytes to postcode_restaurants.json
[success] Total time: 367 s, completed Feb 26, 2017 11:36:44 AM
```
