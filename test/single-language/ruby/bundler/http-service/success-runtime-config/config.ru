require 'rack'
require 'webrick'
require_relative 'lib/configuration'
require_relative 'lib/router'
router = Fixture::Router.new(Fixture::Configuration.new)
run ->(environment) {
  request = Rack::Request.new(environment)
  router.route(request.path_info, request.query_string)
}
