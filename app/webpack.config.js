const path = require('path');
const webpack = require('webpack');

function jquery() {
    return new webpack.ProvidePlugin({
        $: "jquery",
        jQuery: "jquery"
    });
}

module.exports = {
    mode: "production",
    entry: {
        main: "./src/main/js/index.js",
        map: "./src/main/js/map-page.js",
    },
    output: {
        path: path.resolve(__dirname, "src/main/assets/assets/js"),
        filename: "[name].js",
    },
    plugins: [
        jquery()
    ]
};
