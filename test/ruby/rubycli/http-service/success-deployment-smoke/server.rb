require_relative 'lib/configuration'
require_relative 'lib/router'
require_relative 'lib/http_server'
configuration = Fixture::Configuration.new
Fixture::HttpServer.new(configuration, Fixture::Router.new(configuration)).run
