package cn.dental.shared.web;
public record ApiResponse<T>(T data,String request_id) {}
