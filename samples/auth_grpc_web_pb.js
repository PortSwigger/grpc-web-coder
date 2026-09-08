/**
 * @fileoverview grpc-web generated client stub for auth
 * @enhanceable
 * @public
 * Sample file for exercising "Analyze gRPC-Web Endpoints" without a live target.
 * Shaped like real protoc-gen-grpc-web output, including a minified tail.
 */

const grpc = {};
grpc.web = require('grpc-web');
const jspb = require('google-protobuf');
const proto = { auth: {}, admin: {} };

/**
 * @param {string} hostname
 * @constructor
 */
proto.auth.AuthServiceClient = function(hostname, credentials, options) {
  this.client_ = new grpc.web.GrpcWebClientBase(options);
  this.hostname_ = hostname.replace(/\/+$/, '');
};

// Some ordinary application URLs. These are not gRPC routes and must not be listed.
proto.auth.AuthServiceClient.prototype.assets_ = {
  logo: '/static/logo.svg',
  health: '/api/health',
  callback: '/login/callback'
};

/**
 * @const
 * @type {!grpc.web.MethodDescriptor<
 *   !proto.auth.LoginRequest,
 *   !proto.auth.LoginResponse>}
 */
const methodDescriptor_AuthService_Login = new grpc.web.MethodDescriptor(
  '/auth.AuthService/Login',
  grpc.web.MethodType.UNARY,
  proto.auth.LoginRequest,
  proto.auth.LoginResponse,
  function(request) {
    return request.serializeBinary();
  },
  proto.auth.LoginResponse.deserializeBinary
);

proto.auth.AuthServiceClient.prototype.login = function(request, metadata, callback) {
  return this.client_.rpcCall(this.hostname_ +
      '/auth.AuthService/Login',
      request,
      metadata || {},
      methodDescriptor_AuthService_Login,
      callback);
};

const methodDescriptor_AuthService_RefreshToken = new grpc.web.MethodDescriptor(
  '/auth.AuthService/RefreshToken',
  grpc.web.MethodType.UNARY,
  proto.auth.RefreshRequest,
  proto.auth.LoginResponse
);

const methodDescriptor_AuthService_StreamEvents = new grpc.web.MethodDescriptor(
  '/auth.AuthService/StreamEvents',
  grpc.web.MethodType.SERVER_STREAMING,
  proto.auth.EventRequest,
  proto.auth.Event
);

proto.auth.AuthServiceClient.prototype.streamEvents = function(request, metadata) {
  return this.client_.serverStreaming(this.hostname_ +
      '/auth.AuthService/StreamEvents',
      request,
      metadata || {},
      methodDescriptor_AuthService_StreamEvents);
};

/**
 * An admin service the UI never calls. This is the interesting kind of find.
 */
const methodDescriptor_AdminService_DeleteUser = new grpc.web.MethodDescriptor(
  '/admin.AdminService/DeleteUser',
  grpc.web.MethodType.UNARY,
  proto.admin.DeleteUserRequest,
  proto.admin.DeleteUserResponse
);

const methodDescriptor_AdminService_ImpersonateUser = new grpc.web.MethodDescriptor(
  '/admin.AdminService/ImpersonateUser',
  grpc.web.MethodType.UNARY,
  proto.admin.ImpersonateRequest,
  proto.admin.ImpersonateResponse
);

/**
 * A service compiled from a .proto with no package declaration, so the path has
 * no dotted segment. Only recognised because it sits in a gRPC call site.
 */
proto.LegacyClient.prototype.ping = function(request, metadata, callback) {
  return this.client_.rpcCall(this.hostname_ + '/LegacyService/Ping',
      request, metadata || {}, methodDescriptor_Legacy_Ping, callback);
};

// ---------------------------------------------------------------- messages

/**
 * @param {string} value
 * @return {!proto.auth.LoginRequest} returns this
 */
proto.auth.LoginRequest.prototype.setUserName = function(value) {
  return jspb.Message.setProto3StringField(this, 1, value);
};

proto.auth.LoginRequest.prototype.setPassword = function(value) {
  return jspb.Message.setProto3StringField(this, 2, value);
};

proto.auth.LoginRequest.prototype.setRememberMe = function(value) {
  return jspb.Message.setProto3BooleanField(this, 3, value);
};

proto.auth.LoginRequest.prototype.setTotpCode = function(value) {
  return jspb.Message.setProto3IntField(this, 4, value);
};

proto.auth.LoginRequest.prototype.setDeviceFingerprint = function(value) {
  return jspb.Message.setProto3BytesField(this, 5, value);
};

proto.auth.LoginResponse.prototype.setAccessToken = function(value) {
  return jspb.Message.setProto3StringField(this, 1, value);
};

proto.auth.LoginResponse.prototype.setExpiresAt = function(value) {
  return jspb.Message.setProto3IntField(this, 2, value);
};

proto.auth.LoginResponse.prototype.setProfile = function(value) {
  return jspb.Message.setWrapperField(this, 3, value);
};

/**
 * A repeated field, which protoc emits as a getter rather than a plain setter.
 */
proto.auth.LoginResponse.prototype.getRolesList = function() {
  return jspb.Message.getRepeatedField(this, 4);
};

proto.admin.DeleteUserRequest.prototype.setUserId = function(value) {
  return jspb.Message.setProto3IntField(this, 1, value);
};

proto.admin.DeleteUserRequest.prototype.setHardDelete = function(value) {
  return jspb.Message.setProto3BooleanField(this, 2, value);
};

proto.admin.ImpersonateRequest.prototype.setTargetUserId = function(value) {
  return jspb.Message.setProto3IntField(this, 1, value);
};

proto.admin.ImpersonateRequest.prototype.setReason = function(value) {
  return jspb.Message.setProto3StringField(this, 2, value);
};

// A minified tail, the way a bundler would ship it. Handled without a beautifier.
proto.auth.Event.prototype.setKind=function(value){return jspb.Message.setProto3IntField(this,1,value);};proto.auth.Event.prototype.setPayload=function(value){return jspb.Message.setProto3BytesField(this,2,value);};proto.auth.Event.prototype.setTimestampMs=function(value){return jspb.Message.setProto3IntField(this,3,value);};

module.exports = proto.auth;
