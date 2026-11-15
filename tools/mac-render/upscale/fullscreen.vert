// One triangle that covers the whole target; draw with glDrawArrays(GL_TRIANGLES, 0, 3) and an empty VAO.
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
