const grpc = require("grpc");
const messages = require("./autogenerated/KeyspaceMessages");
const service = require("./autogenerated/KeyspaceServiceProto");

function KeyspaceService(uri, credentials) {
    this.uri = uri;
    this.credentials = credentials;
    this.stub = new service.KeyspaceServiceClient(uri, grpc.credentials.createInsecure());
}


KeyspaceService.prototype.retrieve = function () {
    const retrieveRequest = new messages.Keyspace.Retrieve.Req();
    return new Promise((resolve, reject) => {
        this.stub.retrieve(retrieveRequest, (err, resp) => {
            if (err) reject(err);
            else resolve(resp.getNamesList());
        });
    })
}

KeyspaceService.prototype.delete = function (keyspace) {
    const deleteRequest = new messages.Keyspace.Delete.Req();
    deleteRequest.setName(keyspace);
    return new Promise((resolve, reject) => {
        this.stub.delete(deleteRequest, (err) => {
            if (err) reject(err);
            else resolve();
        });
    });
}

KeyspaceService.prototype.close = function close() {
    grpc.closeClient(this.stub);
}

module.exports = KeyspaceService;